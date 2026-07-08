package club.mineclub.watchparty.packet;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe allocator for fake (client-side-only) entity IDs.
 * <p>
 * Real server entity IDs start near 1 and grow upward. We occupy a range
 * far into the negative integers to minimise any chance of collision.
 * The server never learns about these IDs; they exist only in packets we
 * forge and send directly to individual clients.
 */
public final class FakeEntityIdAllocator {

    /**
     * Starting point well below any real server entity ID.
     * Integer.MIN_VALUE / 2 ≈ -1 073 741 824.
     */
    private static final int START = Integer.MIN_VALUE / 2;

    private final AtomicInteger counter = new AtomicInteger(START);

    /**
     * Allocates a unique fake entity ID.
     *
     * @return a negative integer unlikely to collide with any real entity.
     */
    public int allocate() {
        int id = counter.getAndIncrement();
        if (id >= 0) {
            // Extremely unlikely overflow guard
            throw new IllegalStateException(
                    "FakeEntityIdAllocator overflowed — more than 1 billion fake entities allocated.");
        }
        return id;
    }
}
