package com.privmsg.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * El sobre es la columna vertebral del protocolo: si el troceado o el parseo
 * fallan, no llega nada. Estos tests corren en la JVM, sin dispositivo.
 */
class EnvelopeTest {

    private val fp = "3FA2 91C4 0B77 D2E0 5A19"

    @Test
    fun `mensaje corto viaja en un solo fragmento`() {
        val parts = Envelope.build(fp, "Pedro", Kind.TEXT, "hola".toByteArray())
        assertEquals(1, parts.size)

        val parsed = Envelope.parse(parts[0])
        assertNotNull(parsed)
        parsed!!
        assertEquals(fp.replace(" ", ""), parsed.senderFp)
        assertEquals("Pedro", parsed.nickname)
        assertEquals(Kind.TEXT, parsed.kind)
        assertEquals(0, parsed.index)
        assertEquals(1, parsed.total)
        assertEquals("", parsed.groupId)
        assertEquals(0L, parsed.ttlSeconds)
        assertEquals("hola", String(parsed.payload))
    }

    @Test
    fun `grupo y ttl sobreviven al ida y vuelta`() {
        val parts = Envelope.build(
            fp, "Pedro", Kind.TEXT, "trabajo".toByteArray(),
            groupId = "a1b2c3d4", ttlSeconds = 3600L,
        )
        val parsed = Envelope.parse(parts[0])!!
        assertEquals("a1b2c3d4", parsed.groupId)
        assertEquals(3600L, parsed.ttlSeconds)
    }

    @Test
    fun `una foto se trocea y se reensambla identica`() {
        val photo = Random(42).nextBytes(150_000)
        val parts = Envelope.build(fp, "Pedro", Kind.IMAGE, photo)
        assertTrue("debe trocearse", parts.size > 1)

        val assembler = ChunkAssembler()
        var result: ByteArray? = null
        parts.forEach { part ->
            val parsed = Envelope.parse(part)!!
            assertEquals(parts.size, parsed.total)
            assembler.offer(parsed.msgId, parsed.index, parsed.total, parsed.payload)
                ?.let { result = it }
        }
        assertNotNull("debe completarse al llegar el último", result)
        assertArrayEquals(photo, result)
    }

    @Test
    fun `los fragmentos comparten msgId y se numeran en orden`() {
        val parts = Envelope.build(fp, "Pedro", Kind.AUDIO, ByteArray(70_000))
        val parsed = parts.map { Envelope.parse(it)!! }
        assertEquals(1, parsed.map { it.msgId }.distinct().size)
        assertEquals(parsed.indices.toList(), parsed.map { it.index })
    }

    @Test
    fun `reensambla aunque los fragmentos lleguen desordenados`() {
        val data = Random(7).nextBytes(90_000)
        val parsed = Envelope.build(fp, "Pedro", Kind.IMAGE, data).map { Envelope.parse(it)!! }

        val assembler = ChunkAssembler()
        var result: ByteArray? = null
        parsed.shuffled(java.util.Random(1)).forEach { p ->
            assembler.offer(p.msgId, p.index, p.total, p.payload)?.let { result = it }
        }
        assertArrayEquals(data, result)
    }

    @Test
    fun `un fragmento duplicado no rompe el reensamblado`() {
        val data = Random(9).nextBytes(60_000)
        val parsed = Envelope.build(fp, "Pedro", Kind.IMAGE, data).map { Envelope.parse(it)!! }

        val assembler = ChunkAssembler()
        var result: ByteArray? = null
        (parsed + parsed.first()).forEach { p ->
            assembler.offer(p.msgId, p.index, p.total, p.payload)?.let { result = it }
        }
        assertArrayEquals(data, result)
    }

    @Test
    fun `payload vacio produce un fragmento valido`() {
        val parts = Envelope.build(fp, "", Kind.CALL_END, ByteArray(0))
        assertEquals(1, parts.size)
        val parsed = Envelope.parse(parts[0])!!
        assertEquals(Kind.CALL_END, parsed.kind)
        assertEquals(0, parsed.payload.size)
    }

    @Test
    fun `un payload binario con saltos de linea no confunde al parser`() {
        // El payload puede contener \n: el parser debe contar solo la cabecera.
        val tricky = "linea1\nlinea2\nlinea3\nlinea4\nlinea5\nlinea6\nlinea7".toByteArray()
        val parsed = Envelope.parse(Envelope.build(fp, "Pedro", Kind.TEXT, tricky)[0])!!
        assertArrayEquals(tricky, parsed.payload)
    }

    @Test
    fun `el nickname con saltos de linea se sanea`() {
        val parsed = Envelope.parse(
            Envelope.build(fp, "malo\ninyectado", Kind.TEXT, "x".toByteArray())[0],
        )!!
        assertEquals("malo inyectado", parsed.nickname)
        assertEquals("x", String(parsed.payload))
    }

    @Test
    fun `basura y cabeceras incompletas se rechazan`() {
        assertNull(Envelope.parse(ByteArray(0)))
        assertNull(Envelope.parse("no soy un sobre".toByteArray()))
        assertNull(Envelope.parse("a\nb\nc\n".toByteArray()))
        // Kind inexistente
        assertNull(Envelope.parse("fp\nnick\nNO_EXISTE\nid\n0\n1\n\n0\ndata".toByteArray()))
        // índice fuera de rango
        assertNull(Envelope.parse("fp\nnick\nTEXT\nid\n5\n2\n\n0\ndata".toByteArray()))
    }

    @Test
    fun `las senales de llamada y de grupo se clasifican bien`() {
        assertTrue(Kind.CALL_OFFER.isCallSignal)
        assertTrue(Kind.CALL_END.isCallSignal)
        assertTrue(Kind.GROUP_INVITE.isGroupControl)
        assertTrue(Kind.TEXT.isVisibleMessage)
        assertTrue(Kind.IMAGE.isVisibleMessage)
        assertTrue(Kind.AUDIO.isVisibleMessage)

        // Lo que no es visible no debe acabar en la conversación.
        assertTrue(!Kind.AVATAR.isVisibleMessage)
        assertTrue(!Kind.CALL_OFFER.isVisibleMessage)
        assertTrue(!Kind.GROUP_UPDATE.isVisibleMessage)
    }

    @Test
    fun `una transferencia incompleta no devuelve nada`() {
        val parsed = Envelope.build(fp, "P", Kind.IMAGE, Random(3).nextBytes(80_000))
            .map { Envelope.parse(it)!! }
        val assembler = ChunkAssembler()
        // Faltando el último fragmento, nunca completa.
        parsed.dropLast(1).forEach { p ->
            assertNull(assembler.offer(p.msgId, p.index, p.total, p.payload))
        }
    }
}
