package ru.otus.hw.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.hw.dto.ProductReservationCreateRequestDto;
import ru.otus.hw.dto.ProductReservationListResponseDto;
import ru.otus.hw.dto.ReservationItemRequestDto;
import ru.otus.hw.dto.mapper.ProductReservationMapper;
import ru.otus.hw.exception.NotFoundException;
import ru.otus.hw.exception.ProductReservationException;
import ru.otus.hw.model.Product;
import ru.otus.hw.model.ProductReservation;
import ru.otus.hw.model.ProductStock;
import ru.otus.hw.repository.ProductRepository;
import ru.otus.hw.repository.ProductReservationRepository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static ru.otus.hw.dto.ReservationStatus.CONFIRMED;
import static ru.otus.hw.dto.ReservationStatus.RELEASED;
import static ru.otus.hw.dto.ReservationStatus.RESERVED;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductReservationServiceImpl implements ProductReservationService {

    private final ProductReservationRepository productReservationRepository;

    private final ProductRepository productRepository;

    private final ProductReservationMapper productReservationMapper;

    @Override
    @Transactional
    public ProductReservationListResponseDto reserve(@NonNull ProductReservationCreateRequestDto request) {
        if (request.items() == null || request.items().isEmpty()) {
            throw new IllegalArgumentException("Reservation items must not be empty");
        }
        log.info("Reserving products for order id: {}, items: {}", request.orderId(), request.items().size());

        Map<Long, AggregatedItem> aggregated = request.items().stream()
                .collect(Collectors.toMap(
                        ReservationItemRequestDto::productId,
                        item ->
                                new AggregatedItem(item.productId(), item.quantity(), item.idempotencyKey()),
                        (a, b) -> {
                            if (!a.idempotencyKey().equals(b.idempotencyKey())) {
                                throw new IllegalArgumentException("Duplicate product id: " + a.productId()
                                        + " with different idempotency keys: " + a.idempotencyKey()
                                        + " and " + b.idempotencyKey());
                            }
                            return new AggregatedItem(a.productId(), a.quantity() + b.quantity(),
                                    a.idempotencyKey());
                        }
                ));

        List<ProductReservation> productReservationList = aggregated.values().stream()
                .map(aggregatedItem -> reserveOrReplay(request.orderId(), aggregatedItem))
                .toList();

        log.info("Products reserved for order id: {}, reservations: {}", request.orderId(),
                productReservationList.size());
        return productReservationMapper.toListResponseDto(request.orderId(), productReservationList);
    }

    /**
     * Идемпотентный replay со сверкой payload'а: существующий резерв по ключу возвращается
     * только при совпадении productId и количества; несовпадение - 409
     * IDEMPOTENCY_KEY_CONFLICT (повтор ключа с другим payload'ом).
     */
    private @NonNull ProductReservation reserveOrReplay(@NonNull Long orderId, @NonNull AggregatedItem item) {
        Optional<ProductReservation> existingProductReservation =
                productReservationRepository.findByIdempotencyKey(item.idempotencyKey());
        if (existingProductReservation.isPresent()) {
            ProductReservation existing = existingProductReservation.get();
            Long existingProductId = existing.getProduct() != null ? existing.getProduct().getId() : null;
            if (!Objects.equals(existingProductId, item.productId())
                    || !Objects.equals(existing.getQuantity(), item.quantity())) {
                throw ProductReservationException.idempotencyKeyConflict(item.productId(),
                        String.format("Idempotency key %s already used with different parameters: "
                                        + "productId=%s quantity=%s, requested productId=%s quantity=%s",
                                item.idempotencyKey(), existingProductId, existing.getQuantity(),
                                item.productId(), item.quantity()));
            }
            log.info("Reservation with idempotency key: {} already exists, returning existing "
                            + "(idempotent replay)",
                    item.idempotencyKey());
            return existing;
        }
        return reserveItem(orderId, item.productId(), item.quantity(), item.idempotencyKey());
    }

    @Override
    @Transactional
    public ProductReservationListResponseDto cancel(Long orderId) {
        log.info("Cancelling reservations for order id: {}", orderId);
        List<ProductReservation> reservations = getReservationsByOrderId(orderId);
        reservations.forEach(reservation -> {
            switch (reservation.getReservationStatus()) {
                case RESERVED -> {
                    ProductStock stock = reservation.getProduct().getProductStock();
                    stock.setAvailableQuantity(stock.getAvailableQuantity() + reservation.getQuantity());
                    stock.setReservedQuantity(stock.getReservedQuantity() - reservation.getQuantity());
                    reservation.setReservationStatus(RELEASED);
                    log.debug("Reservation id: {} released, stock returned", reservation.getId());
                }
                case RELEASED -> log.debug("Reservation id: {} already released, no-op", reservation.getId());
                case CONFIRMED -> throw ProductReservationException.alreadyConfirmed(reservation.getId());
                default -> throw new IllegalArgumentException(
                        "Cannot cancel reservation id: " + reservation.getId()
                                + " in status " + reservation.getReservationStatus());
            }
        });
        productReservationRepository.saveAll(reservations);
        log.info("Reservations cancelled for order id: {}", orderId);
        return productReservationMapper.toListResponseDto(orderId, reservations);
    }

    @Override
    @Transactional
    public ProductReservationListResponseDto confirm(Long orderId) {
        log.info("Confirming reservations for order id: {}", orderId);
        List<ProductReservation> reservations = getReservationsByOrderId(orderId);
        reservations.forEach(reservation -> {
            switch (reservation.getReservationStatus()) {
                case RESERVED -> {
                    ProductStock stock = reservation.getProduct().getProductStock();
                    stock.setReservedQuantity(stock.getReservedQuantity() - reservation.getQuantity());
                    reservation.setReservationStatus(CONFIRMED);
                    log.debug("Reservation id: {} confirmed, stock written off", reservation.getId());
                }
                case CONFIRMED -> log.debug("Reservation id: {} already confirmed, no-op", reservation.getId());
                default -> throw new IllegalArgumentException(
                        "Cannot confirm reservation id: " + reservation.getId()
                                + " in status " + reservation.getReservationStatus());
            }
        });
        productReservationRepository.saveAll(reservations);
        log.info("Reservations confirmed for order id: {}", orderId);
        return productReservationMapper.toListResponseDto(orderId, reservations);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductReservationListResponseDto> findByOrderId(Long orderId) {
        log.debug("Fetching reservations for order id: {}", orderId);
        List<ProductReservation> reservations = productReservationRepository.findByOrderId(orderId);
        if (reservations.isEmpty()) {
            return Optional.empty();
        }
        log.info("Reservations for order id: {} found: {}", orderId, reservations.size());
        return Optional.of(productReservationMapper.toListResponseDto(orderId, reservations));
    }

    @Override
    @Transactional(readOnly = true)
    public ProductReservationListResponseDto getByOrderId(Long orderId) {
        return findByOrderId(orderId)
                .orElseThrow(() -> new NotFoundException(
                        "Product reservations not found for order id: " + orderId));
    }

    private @NonNull ProductReservation reserveItem(Long orderId,
                                                    Long productId,
                                                    Integer quantity,
                                                    String idempotencyKey) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new NotFoundException("Product not found with id: " + productId));
        ProductStock stock = product.getProductStock();
        if (stock == null || stock.getAvailableQuantity() < quantity) {
            throw ProductReservationException.insufficientStock(
                    productId, quantity, stock != null ? stock.getAvailableQuantity() : 0);
        }
        stock.setAvailableQuantity(stock.getAvailableQuantity() - quantity);
        stock.setReservedQuantity(stock.getReservedQuantity() + quantity);
        ProductReservation reservation = productReservationMapper.toEntity(orderId, quantity, idempotencyKey);
        reservation.setProduct(product);
        reservation.setReservationStatus(RESERVED);
        return productReservationRepository.save(reservation);
    }

    private @NonNull List<ProductReservation> getReservationsByOrderId(Long orderId) {
        List<ProductReservation> reservations = productReservationRepository.findByOrderId(orderId);
        if (reservations.isEmpty()) {
            throw new NotFoundException("Product reservations not found for order id: " + orderId);
        }
        return reservations;
    }

    private record AggregatedItem(Long productId, Integer quantity, String idempotencyKey) {
    }

}
