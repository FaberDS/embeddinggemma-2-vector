package dev.pocketask

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ChatMessageUiTests {
    @Test fun sourcePreviewsAreAbsentWhileAnsweringAndOnlyCitedImagesAppearAfterCompletion() = runComposeUiTest {
        val document = Attachment("doc", "Report.pdf", "/unopened/report.pdf", "application/pdf")
        val photos = (1..2).map { Attachment("photo-$it", "Photo $it.jpg", "/unopened/$it.jpg", "image/jpeg") }
        val sources = listOf(Evidence("page", document.id, document.name, 1, "Passage", null, emptyList())) +
            photos.map { Evidence(it.id, it.id, it.name, null, "", it.path, emptyList()) }
        val answer = mutableStateOf(Answer("reply", "Find the picture", listOf(document) + photos,
            text = "The flower is in this picture [S3].", sources = sources, status = "Answering"))
        setContent { MaterialTheme { Column(Modifier.width(360.dp)) {
            ModelMessage(answer.value, 1700000000000, "Answering", { _, _ -> }, {})
        } } }
        onAllNodesWithTag("chat.source.image").assertCountEquals(0)
        onAllNodesWithTag("chat.source.thumbnail").assertCountEquals(0)
        onAllNodesWithTag("chat.source.document").assertCountEquals(0)
        runOnIdle { answer.value = answer.value.copy(status = "Completed") }
        onNodeWithTag("chat.source.image").assertIsDisplayed()
        onNodeWithText("[S3] Photo 2.jpg · Open").assertIsDisplayed()
        onAllNodesWithTag("chat.source.thumbnail").assertCountEquals(0)
        onAllNodesWithTag("chat.source.document").assertCountEquals(0)
        onNodeWithText("[S2] Photo 1.jpg · Open").assertDoesNotExist()
    }

    @Test fun userAndModelBubblesAlignOppositelyAndDocumentLinksOpenTheOriginalPage() = runComposeUiTest {
        val pdf = Attachment("pdf", "Report.pdf", "/earlier-chat/report.pdf", "application/pdf")
        val answer = Answer("turn", "Summarize the report", listOf(pdf), text = "A brief summary [S1].", status = "Completed", modelId = "answer",
            sources = listOf(Evidence("page", pdf.id, pdf.name, 2, "", "/earlier-chat/page-1.jpg", emptyList())))
        val opened = mutableListOf<Pair<String, Int>>()
        setContent { MaterialTheme { Column(Modifier.width(360.dp)) {
            UserMessage(answer, 1700000000000, { path, page -> opened += path to page })
            ModelMessage(answer, 1700000000000, null, { path, page -> opened += path to page }, {})
        } } }
        val user = onNodeWithTag("chat.user.turn").fetchSemanticsNode().boundsInRoot
        val model = onNodeWithTag("chat.reply.turn").fetchSemanticsNode().boundsInRoot
        assertTrue(user.left > model.left)
        assertTrue(user.right > model.right)
        onNodeWithText("Gemma 4 E2B IT").assertIsDisplayed()
        onNodeWithTag("chat.source.document").performClick()
        runOnIdle { assertEquals(listOf(pdf.path to 2), opened) }
        onAllNodesWithTag("chat.source.image").assertCountEquals(0)
    }

    @Test fun aSavedTextDescriptionDisplaysItsLinkedImageAndOpensTheOriginal() = runComposeUiTest {
        val photo = Attachment("photo", "Garden.jpg", "/earlier-chat/garden.jpg", "image/jpeg")
        val answer = Answer("turn", "Describe it", listOf(photo), text = "A garden [S1].", status = "Completed",
            sources = listOf(Evidence("photo-description", photo.id, photo.name, null, "A garden with flowers", null, emptyList(), previewImage = photo.path)))
        var opened: String? = null
        setContent { MaterialTheme { Column(Modifier.width(360.dp)) {
            ModelMessage(answer, 1700000000000, null, { path, _ -> opened = path }, {})
        } } }
        onNodeWithTag("chat.source.image").assertIsDisplayed().performClick()
        runOnIdle { assertEquals(photo.path, opened) }
        onAllNodesWithTag("chat.source.thumbnail").assertCountEquals(0)
    }

    @Test fun multipleImagesUseThumbnailsAndIndependentOpenLinks() = runComposeUiTest {
        val photos = List(2) { Attachment("photo-$it", "Photo $it.jpg", "/earlier-chat/$it.jpg", "image/jpeg") }
        val answer = Answer("turn", "Compare", photos, text = "Comparison [S1] [S2].", status = "Completed",
            sources = photos.map { Evidence(it.id, it.id, it.name, null, "", it.path, emptyList()) })
        var opened: String? = null
        setContent { MaterialTheme { Column(Modifier.width(360.dp)) {
            ModelMessage(answer, 1700000000000, null, { path, _ -> opened = path }, {})
        } } }
        onAllNodesWithTag("chat.source.thumbnail").assertCountEquals(2)
        onAllNodesWithText("Open")[1].performClick()
        runOnIdle { assertEquals(photos[1].path, opened) }
        onAllNodesWithTag("chat.source.image").assertCountEquals(0)
    }
}
