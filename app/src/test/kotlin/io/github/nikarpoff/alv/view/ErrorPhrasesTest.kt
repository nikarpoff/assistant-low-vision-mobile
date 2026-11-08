package io.github.nikarpoff.alv.view

import io.github.nikarpoff.alv.R
import io.github.nikarpoff.alv.model.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.xml.parsers.DocumentBuilderFactory

class ErrorPhrasesTest {
    // Unit tests run in the module directory `app`
    private val moduleDir = listOf(File("."), File("app")).first { File(it, "src/main/res").isDirectory }
    private val rawDir = File(moduleDir, "src/main/res/raw")

    /** `id: "текст"` from `phrases/phrases.yaml`. */
    private val yamlPhrases: Map<String, String> by lazy {
        File(moduleDir.canonicalFile.parentFile, "phrases/phrases.yaml").readLines()
            .mapNotNull { YAML_LINE.matchEntire(it) }
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** `phrase_<id>` strings from `res/values/strings.xml`. */
    private val strings: Map<String, String> by lazy {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(moduleDir, "src/main/res/values/strings.xml"))
        val nodes = document.getElementsByTagName("string")
        (0 until nodes.length).map { nodes.item(it) }
            .associate { it.attributes.getNamedItem("name").nodeValue to it.textContent }
    }

    private val errorPhrases: List<Phrase> =
        ErrorCode.entries.flatMap { listOf(ErrorPhrases.forCode(it), ErrorPhrases.forCode(it, question = true)) } +
            ErrorPhrases.forCode(null) + ErrorPhrases.noConnection

    private val systemPhrases = listOf(
        SystemPhrases.passiveOn,
        SystemPhrases.passiveOff,
        SystemPhrases.permissionRationale,
        SystemPhrases.permissionDenied,
    )

    @Test
    fun phraseTextsFollowProtocolTable() {
        val expected = mapOf(
            ErrorCode.BAD_REQUEST to "Ошибка запроса",
            ErrorCode.UNSUPPORTED_PROTOCOL to "Обновите приложение",
            ErrorCode.PAYLOAD_TOO_LARGE to "Ошибка запроса",
            ErrorCode.BUSY to "Сервер занят, попробуйте ещё раз",
            ErrorCode.ASR_EMPTY to "Не расслышал вопрос",
            ErrorCode.ASR_UNAVAILABLE to "Не могу распознать вопрос",
            ErrorCode.DETECTOR_UNAVAILABLE to "Не удалось рассмотреть обстановку",
            ErrorCode.UPSTREAM_TIMEOUT to "Сервер не ответил вовремя",
            ErrorCode.INTERNAL to "Ошибка сервера",
        )

        assertEquals(ErrorCode.entries.toSet(), expected.keys)
        expected.forEach { (code, text) -> assertEquals(code.wire, text, yamlPhrases[ErrorPhrases.forCode(code).id]) }
    }

    @Test
    fun tooLargeQuestionHasOwnPhrase() {
        val phrase = ErrorPhrases.forCode(ErrorCode.PAYLOAD_TOO_LARGE, question = true)

        assertEquals("Слишком длинный вопрос", yamlPhrases[phrase.id])
    }

    @Test
    fun unknownCodeGetsInternalPhrase() {
        assertEquals(ErrorPhrases.internal, ErrorPhrases.forCode(ErrorCode.fromWire("something_new")))
    }

    @Test
    fun noConnectionPhrase() {
        assertEquals("Нет связи с сервером", yamlPhrases[ErrorPhrases.noConnection.id])
    }

    @Test
    fun everyPhraseHasYamlEntryWavAndString() {
        (errorPhrases + systemPhrases).distinct().forEach { phrase ->
            val text = requireText(phrase.id)

            assertEquals(phrase.id, text, strings["phrase_${phrase.id}"])
            assertWav(File(rawDir, "phrase_${phrase.id}.wav"))
            assertEquals(phrase.id, R.raw::class.java.getField("phrase_${phrase.id}").getInt(null), phrase.raw)
            assertEquals(phrase.id, R.string::class.java.getField("phrase_${phrase.id}").getInt(null), phrase.text)
        }
    }

    @Test
    fun everyYamlPhraseIsShipped() {
        assertTrue("phrases.yaml is empty", yamlPhrases.isNotEmpty())
        val known = (errorPhrases + systemPhrases).map { it.id }.toSet()

        assertEquals(yamlPhrases.keys, known)
        val wavs = rawDir.listFiles { file -> file.name.startsWith("phrase_") }.orEmpty()
            .map { it.name.removePrefix("phrase_").removeSuffix(".wav") }.toSet()
        assertEquals(yamlPhrases.keys, wavs)
    }

    /** RIFF/WAVE, PCM, mono, 16 bit, with sound. */
    private fun assertWav(file: File) {
        assertTrue("${file.name} is missing", file.isFile)
        val bytes = file.readBytes()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(file.name, "RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(file.name, "WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals(file.name, 1.toShort(), header.getShort(20)) // PCM
        assertEquals(file.name, 1.toShort(), header.getShort(22)) // mono
        assertEquals(file.name, 16.toShort(), header.getShort(34)) // 16 bit
        assertTrue("${file.name} has no sound", bytes.size > WAV_HEADER)
    }

    private fun requireText(id: String): String {
        val text = yamlPhrases[id]
        assertNotNull("$id is missing in phrases.yaml", text)
        return text!!
    }

    private companion object {
        val YAML_LINE = Regex("""^([a-z][a-z0-9_]*):\s*"(.*)"\s*$""")
        const val WAV_HEADER = 44
    }
}
