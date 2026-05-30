#pragma once

#include <vector>
#include <atomic>
#include <cstdint>
#include <algorithm>
#include <cstring>

template <typename T>
class AudioCircularBuffer {
public:
    explicit AudioCircularBuffer(size_t capacity = 88200) 
        : capacity_(capacity + 1), 
          buffer_(capacity_), 
          head_(0), 
          tail_(0) {}

    ~AudioCircularBuffer() = default;

    // Non-copyable, non-movable
    AudioCircularBuffer(const AudioCircularBuffer&) = delete;
    AudioCircularBuffer& operator=(const AudioCircularBuffer&) = delete;

    bool push(const T* data, size_t count) {
        size_t current_tail = tail_.load(std::memory_order_relaxed);
        size_t next_tail = (current_tail + count) % capacity_;
        size_t current_head = head_.load(std::memory_order_acquire);

        // Calculate available space
        size_t space = capacity_ - 1 - ((current_tail - current_head + capacity_) % capacity_);
        if (space < count) {
            return false; // Not enough space
        }

        size_t first_part = std::min(count, capacity_ - current_tail);
        std::memcpy(&buffer_[current_tail], data, first_part * sizeof(T));
        if (first_part < count) {
            std::memcpy(&buffer_[0], data + first_part, (count - first_part) * sizeof(T));
        }

        tail_.store(next_tail, std::memory_order_release);
        return true;
    }

    size_t pop(T* out, size_t maxCount) {
        size_t current_head = head_.load(std::memory_order_relaxed);
        size_t current_tail = tail_.load(std::memory_order_acquire);

        if (current_head == current_tail) {
            return 0; // Empty
        }

        size_t available_data = (current_tail - current_head + capacity_) % capacity_;
        size_t to_read = std::min(available_data, maxCount);

        size_t first_part = std::min(to_read, capacity_ - current_head);
        std::memcpy(out, &buffer_[current_head], first_part * sizeof(T));
        if (first_part < to_read) {
            std::memcpy(out + first_part, &buffer_[0], (to_read - first_part) * sizeof(T));
        }

        head_.store((current_head + to_read) % capacity_, std::memory_order_release);
        return to_read;
    }

    size_t available() const {
        size_t current_head = head_.load(std::memory_order_acquire);
        size_t current_tail = tail_.load(std::memory_order_acquire);
        return (current_tail - current_head + capacity_) % capacity_;
    }

    void reset() {
        head_.store(0, std::memory_order_relaxed);
        tail_.store(0, std::memory_order_release);
    }

private:
    size_t capacity_;
    std::vector<T> buffer_;
    std::atomic<size_t> head_;
    std::atomic<size_t> tail_;
};
