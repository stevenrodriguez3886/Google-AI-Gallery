#include "audio_buffer.h"

// Instantiate the template for int16_t since that's what we use for PCM audio
template class AudioCircularBuffer<int16_t>;
