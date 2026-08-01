package com.example.multiroomspeaker

/**
 * Simple binary framing used over each TCP connection between Host and Client.
 * Every message starts with one type byte, followed by a type-specific payload.
 */
object Protocol {
    const val PORT = 8991

    const val MSG_SYNC_REQUEST: Byte = 0x01   // client -> host : [T1: Long]
    const val MSG_SYNC_RESPONSE: Byte = 0x02  // host -> client : [T1: Long][Thost: Long]
    const val MSG_AUDIO_HEADER: Byte = 0x03   // host -> client : [sampleRate:Int][channels:Int][bitsPerSample:Int]
    const val MSG_AUDIO_CHUNK: Byte = 0x04    // host -> client : [targetHostTimeNanos:Long][length:Int][payload]
    const val MSG_STOP: Byte = 0x05           // host -> client : (no payload)

    // How many round trips to average when estimating clock offset with the host.
    const val SYNC_SAMPLES = 8

    // Size of each PCM chunk streamed to clients, in bytes.
    const val CHUNK_SIZE_BYTES = 4096

    // Extra lead time (nanoseconds) given to a chunk's target playback time so that
    // network jitter + buffering has room before the deadline. Tune this up if you
    // hear stutter, down if you hear it lagging noticeably behind the host.
    const val PLAYOUT_DELAY_NANOS = 300_000_000L // 300ms
}
