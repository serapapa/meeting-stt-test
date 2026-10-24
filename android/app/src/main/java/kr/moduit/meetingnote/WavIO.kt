package kr.moduit.meetingnote

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16kHz 모노 16비트 WAV 쓰기 (녹음하면서 계속 덧붙임) */
class WavWriter(val file: File, private val sampleRate: Int = 16000) {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L

    init {
        raf.setLength(0)
        raf.write(ByteArray(44))
    }

    fun write(samples: FloatArray) {
        val bb = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            val v = (s.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bb.putShort(v)
        }
        raf.write(bb.array())
        dataBytes += samples.size * 2
    }

    fun close() {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt((36 + dataBytes).toInt())
        h.put("WAVE".toByteArray()); h.put("fmt ".toByteArray())
        h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(sampleRate); h.putInt(sampleRate * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(dataBytes.toInt())
        raf.seek(0); raf.write(h.array())
        raf.close()
    }
}

/** 우리가 저장한 WAV(16kHz 모노 16비트)를 조금씩 읽기 */
class WavReader16k(file: File) {
    private val raf = RandomAccessFile(file, "r")
    val totalSamples: Long

    init {
        // 'data' 덩어리 찾기
        raf.seek(12)
        var dataLen = 0L
        while (raf.filePointer < raf.length() - 8) {
            val id = ByteArray(4); raf.readFully(id)
            val lenBytes = ByteArray(4); raf.readFully(lenBytes)
            val len = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            if (String(id) == "data") { dataLen = len; break }
            raf.seek(raf.filePointer + len)
        }
        if (dataLen == 0L) dataLen = raf.length() - raf.filePointer
        totalSamples = dataLen / 2
    }

    /** 최대 n개 읽기. 끝이면 null */
    fun read(n: Int): FloatArray? {
        val bytes = ByteArray(n * 2)
        val got = raf.read(bytes)
        if (got <= 0) return null
        val bb = ByteBuffer.wrap(bytes, 0, got).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(got / 2)
        for (i in out.indices) out[i] = bb.short / 32768f
        return out
    }

    fun close() = raf.close()
}
