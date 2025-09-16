package graphics.cinnabar.lib.datastructures;

import org.jetbrains.annotations.Nullable;

public class SpliceableLinkedList<T> {
    @Nullable
    private Node<T> head;
    @Nullable
    private Node<T> tail;

    public void add(final Node<T> node) {
        if (node.linked) {
            throw new IllegalArgumentException();
        }
        assert node.next == null;
        assert node.prev == null;
        node.linked = true;
        if (head == null) {
            head = node;
            tail = node;
            return;
        }
        assert tail != null;
        tail.next = node;
        node.prev = tail;
        tail = node;
    }

    public void remove(final Node<T> node) {
        if (!node.linked) {
            throw new IllegalArgumentException();
        }
        if (node == head) {
            assert node.prev == null;
            head = node.next;
            if (head != null) {
                head.prev = null;
            }
        }
        if (node == tail) {
            assert node.next == null;
            tail = node.prev;
            if (tail != null) {
                tail.next = null;
            }
        }
        if (node.next != null) {
            node.next.prev = node.prev;
        }
        if (node.prev != null) {
            node.prev.next = node.next;
        }
        node.next = null;
        node.prev = null;
        node.linked = false;
        assert node != head;
        assert node != tail;
        assert head == null || (head.linked && head.prev == null);
        assert tail == null || (tail.linked && tail.next == null);
    }

    @Nullable
    public Node<T> peekFirst() {
        return head;
    }

    @Nullable
    public Node<T> removeFirst() {
        if (head == null) {
            return null;
        }
        final var prevHead = head;
        @Nullable final var nextHead = head.next;
        if (nextHead != null) {
            nextHead.prev = null;
        } else {
            assert head == tail;
            tail = null;
        }
        head = nextHead;
        prevHead.prev = null;
        prevHead.next = null;
        prevHead.linked = false;
        assert prevHead != head;
        assert prevHead != tail;
        assert head == null || (head.linked && head.prev == null);
        assert tail == null || (tail.linked && tail.next == null);
        return prevHead;
    }

    public boolean empty() {
        return head == null;
    }

    public void sliceEnd(SpliceableLinkedList<T> other) {
        if (other.head == null) {
            return;
        }
        if (head == null) {
            head = other.head;
            tail = other.tail;
            other.head = null;
            other.tail = null;
            return;
        }
        assert tail != null;
        assert other.tail != null;
        tail.next = other.head;
        other.head.prev = tail;
        tail = other.tail;
        other.head = null;
        other.tail = null;
    }

    public static class Node<T> {
        public final T data;
        @Nullable
        private Node<T> prev;
        @Nullable
        private Node<T> next;
        private boolean linked = false;

        public Node(T data) {
            this.data = data;
        }

        public boolean linked() {
            return linked;
        }

        @Nullable
        public Node<T> next() {
            return next;
        }
    }
}
