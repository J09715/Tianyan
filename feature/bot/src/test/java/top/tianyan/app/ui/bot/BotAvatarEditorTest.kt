package top.tianyan.app.ui.bot

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import android.util.Base64
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.theme.TianyanTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BotAvatarEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `reset replaces selected photo with generated avatar`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", it) }
        val seed = mutableStateOf("data:image/png;base64," + Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP))
        compose.setContent {
            TianyanTheme {
                BotAvatarEditor(seed.value, "阿策", "#3D5AFE", true, onChange = { seed.value = it }, onLoading = {})
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("bot-custom-avatar").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.fbot_avatar_reset)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("bot-custom-avatar").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("bot-generated-avatar").assertExists()
        compose.runOnIdle { assertEquals("阿策", seed.value) }
    }
}
