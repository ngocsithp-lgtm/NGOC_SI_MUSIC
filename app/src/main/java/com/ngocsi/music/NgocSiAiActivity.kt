package com.ngocsi.music

import android.Manifest
import android.media.AudioManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContentUris
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import java.io.ByteArrayOutputStream
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.text.Normalizer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class AiPreviewMessage(val isUser: Boolean, val text: String)

private data class PendingWakeWordContextTurn(
    val turnId: Long,
    val question: String,
    val answer: String
)

private const val AI_CHAT_PREFS_NAME = NGOC_SI_AI_CHAT_PREFERENCES_NAME
private const val AI_CHAT_MESSAGES_KEY = "chat_messages_v1"
private const val AI_CHAT_CONVERSATION_KEY = "api_conversation_v1"
private const val AI_VOICE_REPLY_KEY = "voice_replies_enabled_v1"
private const val AI_WAKEWORD_LAST_DISPLAYED_TURN_KEY = "wake_word_last_displayed_turn_id"
private const val AI_WAKEWORD_LAST_CONTEXT_TURN_KEY = "wake_word_last_context_turn_id"
private const val MAX_SAVED_AI_UI_MESSAGES = 60
private const val MAX_SAVED_AI_MESSAGE_CHARS = 4_000

private fun buildOfflineSingerComparisonClarification(previousAssistantReply: String?): String? {
    val previous = previousAssistantReply?.trim().orEmpty()
    if (previous.isBlank()) return null
    val context = previous.lowercase(Locale.ROOT)
    if (listOf("bolero", "video", "ca sĩ", "ca si").none(context::contains)) return null
    return "Để so sánh công bằng và chính xác, mình cần xác định ca sĩ đang hát và người đối chiếu. " +
        "Bạn muốn so sánh ca sĩ đang hát với ca sĩ nào? Hãy nêu tên ca sĩ còn lại nhé. " +
        "Khi có đủ tên và bản thu âm phù hợp, mình sẽ đối chiếu màu giọng, độ dày và độ vang, " +
        "cách nhả chữ, luyến láy, độ rung, hơi thở, nhịp phách và cách xử lý cao trào. " +
        "Mình sẽ không tự kết luận về chất giọng khi chưa có bản thu âm để nghe."
}

private fun buildOfflineBoleroVideoAnalysis(
    previousAssistantReply: String?,
    remoteError: String? = null
): String? {
    val previous = previousAssistantReply?.trim().orEmpty()
    if (previous.isBlank()) return null
    val context = previous.lowercase(Locale.ROOT)
    if (listOf("bolero", "video", "ca sĩ", "ca si").none(context::contains)) return null

    val error = remoteError.orEmpty().lowercase(Locale.ROOT)
    val limitation = when {
        "quota" in error || "429" in error || "hạn mức" in error ->
            "Gemini đang bị giới hạn hạn mức nên phần dưới đây là gợi ý sáng tạo dựa trên ngữ cảnh, chưa phải nhận xét xác thực từng cảnh."
        "network" in error || "timeout" in error || "mạng" in error ->
            "Chat trực tuyến đang gặp sự cố; mình sẽ đưa ra gợi ý chung từ phần hội thoại đã có."
        else ->
            "Hiện mình chỉ có ngữ cảnh hội thoại, chưa có dữ liệu từng khung hình hoặc lời bài hát để xác thực nội dung video."
    }

    return "Mình tiếp tục phần trước: “${previous.take(150)}”. $limitation " +
        "Mình chưa được cung cấp khung hình hoặc lời ca thực tế của video, nên các gợi ý dưới đây chỉ là minh họa.\n\n" +
        "1. Cảm xúc: xác định đoạn nhạc đang nghiêng về nhớ nhung, tiếc nuối hay chia xa; chọn một cảm xúc chủ đạo cho mỗi phân cảnh.\n" +
        "2. Hình ảnh: với nỗi nhớ, thử ánh đèn vàng, mưa nhẹ và con đường vắng; với chia xa, dùng sân ga hoặc khung hình rộng để nhân vật nhỏ giữa không gian.\n" +
        "3. Nhịp dựng: giữ cảnh lâu ở câu hát ngân, chuyển cảnh tại điểm rơi của lời ca hoặc nhịp phách; hạn chế hiệu ứng dày làm mất chất trữ tình.\n" +
        "4. So sánh ca sĩ: đối chiếu màu và độ dày giọng, cách nhả chữ, luyến láy, độ rung và xử lý cao trào; chỉ kết luận khi có cùng bài hoặc cùng đoạn thu âm để so sánh công bằng.\n" +
        "5. Ví dụ dựng cảnh: ở câu hát diễn tả nhớ người cũ, dùng cận cảnh ánh mắt; đến điệp khúc, mở rộng sang phố đêm hoặc con đường mưa để tăng cảm giác cô đơn.\n\n" +
        "Đây là hướng dựng minh họa, không phải mô tả những gì chắc chắn xuất hiện trong video. Gửi tên bài hát hoặc vài câu lời ca, mình sẽ phân tích sát hơn."
}

internal fun localConversationFallback(
    userText: String,
    previousAssistantReply: String? = null,
    allowContinuationFallback: Boolean = true
): String? {
    localNgocSiAiAppHelpFallback(userText)?.let { return it }

    val normalized = stripNgocSiAiDirectIntentWakePrefix(
        Normalizer.normalize(userText.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .replace('đ', 'd')
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    )

    // Stable factual answers are centralized in one deterministic offline knowledge rule set.
    // Uncertain or time-sensitive questions continue to Gemini when online service is available.
    localNgocSiAiKnowledgeFallback(userText)?.let { return it }
    localNgocSiAiMusicKnowledgeFallback(userText)?.let { return it }

    fun matchesAny(vararg phrases: String): Boolean =
        phrases.any { normalized == it || normalized.startsWith("$it ") }

    // Quick everyday exchanges stay on-device and never consume Gemini requests.
    when {
        normalized in setOf(
            "chao", "xin chao", "chao ban", "xin chao ban", "chao ai",
            "chao buoi sang", "xin chao buoi sang", "chao buoi chieu",
            "xin chao buoi chieu", "chao buoi toi", "xin chao buoi toi",
            "good morning", "good evening", "hello", "hi"
        ) ->
            return "Chào bạn. Mình đang nghe đây. Bạn muốn trò chuyện hay cần mình điều khiển nhạc trong ứng dụng?"

        normalized in setOf(
            "cam on", "cam on ban", "cam on nhe", "cam on ban nhieu",
            "cam on rat nhieu", "minh cam on", "toi cam on ban",
            "thanks", "thank you"
        ) ->
            return "Không có gì đâu. Bạn cứ nói điều bạn cần, mình sẽ hỗ trợ trong khả năng của mình."

        normalized in setOf("xin loi", "minh xin loi", "toi xin loi", "sorry") ->
            return "Không sao đâu. Mình vẫn ở đây để cùng bạn xử lý việc cần làm."

        normalized in setOf("ok", "oke", "duoc", "duoc roi", "roi", "hieu roi", "hay do", "tot") ->
            return "Ừ, mình theo ý bạn. Khi cần đi tiếp, cứ nói tự nhiên nhé."

        normalized in setOf(
            "ban lam duoc gi", "ban co the lam gi", "ai lam duoc gi",
            "ngoc si ai lam duoc gi", "ban ho tro duoc gi"
        ) ->
            return "Mình có thể xử lý các lệnh đã tích hợp như phát, tạm dừng hoặc dừng nhạc, chuyển bài, tua, chỉnh âm lượng; mở, tìm và điều khiển YouTube; mở Radio, Google Drive và TV; quản lý hàng đợi, playlist, Yêu thích và hẹn giờ. Câu hỏi kiến thức rộng cần Gemini trực tuyến."

        normalized in setOf("ban ten gi", "ten ban la gi", "ban la ai", "ban la ai vay") ->
            return "Mình là NGỌC SĨ AI, trợ lý tích hợp trong NGỌC SĨ MUSIC."

        normalized in setOf("ban co phai nguoi that khong", "ban la nguoi that a") ->
            return "Mình là AI, không phải con người. Mình có thể trò chuyện và hỗ trợ bạn, nhưng sẽ nói rõ khi không biết hoặc không thể thực hiện việc gì."

        normalized in setOf(
            "ban khoe khong", "ban co khoe khong", "ban co on khong",
            "hom nay ban the nao", "hom nay ban ra sao", "ban the nao",
            "ban dang lam gi", "co ai o do khong"
        ) ->
            return "Mình sẵn sàng hỗ trợ bạn đây. Hôm nay bạn muốn nghe nhạc hay cùng mình giải quyết việc gì?"

        matchesAny(
            "toi buon", "minh buon", "toi thay buon", "minh thay buon",
            "toi co don", "minh co don", "toi thay co don",
            "toi ap luc", "minh ap luc", "toi that vong", "minh that vong",
            "toi chan nan", "minh chan nan", "hom nay khong vui"
        ) ->
            return "Nghe như hôm nay bạn đang không ổn. Mình ở đây để lắng nghe, không phán xét. Điều gì đang khiến bạn nặng lòng nhất?"

        matchesAny("toi met", "minh met", "met qua", "toi kiem suc", "minh kien suc") ->
            return "Có vẻ bạn đang khá mệt. Mình không muốn vội đưa lời khuyên; bạn muốn kể điều gì làm mình mệt nhất, hay muốn nghỉ một chút rồi tính tiếp?"

        matchesAny("toi vui", "minh vui", "vui qua", "hom nay vui", "toi dang vui") ->
            return "Nghe vui đấy. Có chuyện gì tốt vừa xảy ra mà bạn muốn kể mình nghe không?"

        normalized in setOf(
            "toi muon tam su", "minh muon tam su", "hay tam su voi toi",
            "noi chuyen voi toi", "nghe toi noi", "toi muon ke chuyen",
            "minh muon ke chuyen", "toi can nguoi lang nghe"
        ) ->
            return "Được chứ, bạn cứ kể theo cách tự nhiên nhất. Mình sẽ nghe trước; bạn muốn mình chỉ lắng nghe hay cùng tìm cách giải quyết?"

        matchesAny("ban co hieu toi", "ban hieu toi", "ban co lang nghe toi") ->
            return "Mình sẽ cố gắng hiểu từ điều bạn kể và bám sát ngữ cảnh. Nếu mình hiểu lệch, bạn cứ sửa thẳng cho mình nhé."

        matchesAny("ban co the suy luan", "ban co biet suy luan", "ban co thong minh") ->
            return "Khi Gemini trực tuyến còn hoạt động, mình có thể phân tích ngữ cảnh, so sánh các khả năng và đề xuất cách làm. Khi hết hạn mức, chế độ cục bộ chỉ xử lý lệnh và một số hội thoại quen thuộc; mình sẽ không giả vờ đã phân tích được câu hỏi phức tạp."

        matchesAny(
            "tai sao ai tra loi cham", "vi sao ai tra loi cham",
            "ai tra loi cham", "ai phan hoi cham", "gemini tra loi cham",
            "ai khong tra loi duoc", "sao ai khong tra loi"
        ) ->
            return "Câu hỏi tự do cần Gemini trực tuyến. Mạng yếu, máy chủ quá tải hoặc hạn mức miễn phí có thể làm câu trả lời chậm hay không đến. Bản cập nhật giới hạn chờ 25 giây cho câu hỏi thường và 40 giây cho yêu cầu phân tích; lệnh điều khiển nhạc trên điện thoại vẫn chạy trực tiếp."

        matchesAny("tai sao ai het han muc", "sao ai het han muc", "tai sao bi loi 429", "sao chat bi 429") ||
            (normalized.contains("han muc") && (normalized.contains("gemini") || normalized.contains("ai"))) ->
            return "Hạn mức do Google áp dụng theo model và dự án, không phải do lệnh nhạc trên điện thoại. Mình không bật thanh toán và sẽ không gửi lặp yêu cầu khi máy chủ báo hết hạn mức. Các lệnh điều khiển cục bộ vẫn dùng được."

        normalized in setOf(
            "tiep tuc", "tiep tuc di", "tiep tuc nhe",
            "tiep di", "tiep nua", "lam tiep", "lam tiep di", "lam tiep nhe",
            "noi tiep", "noi tiep di", "noi tiep nhe",
            "ke tiep", "ke tiep di"
        ) && allowContinuationFallback -> {
            val previous = previousAssistantReply?.trim().orEmpty()
            buildOfflineBoleroVideoAnalysis(previous)?.let { return it }
            return if (previous.isNotBlank()) {
                "Mình theo mạch vừa trao đổi nhé. Ý gần nhất là: “" + previous.take(220) + "”. Khi chat trực tuyến đang bị giới hạn, mình không muốn tự đoán thêm rồi làm sai ý bạn; bạn nói phần muốn đào sâu, mình sẽ bám đúng phần đó."
            } else {
                "Mình sẵn sàng tiếp tục. Bạn nhắc ngắn gọn chủ đề đang làm dở để mình không đoán sai ý nhé."
            }
        }
    }

    val asksForSingerComparison = listOf("so sanh", "doi chieu", "cach hat cua ca si")
        .any(normalized::contains)
    if (asksForSingerComparison) {
        buildOfflineSingerComparisonClarification(previousAssistantReply)?.let { return it }
    }

    val asksForBoleroAnalysis = normalized.contains("phan tich") ||
        normalized.contains("chi tiet hon") || normalized.contains("noi ro hon") ||
        normalized.contains("vi du")
    if (asksForBoleroAnalysis) {
        buildOfflineBoleroVideoAnalysis(previousAssistantReply)?.let { return it }
    }

    val asksForAppDirection =
        (normalized.contains("chuyen nghiep") || normalized.contains("pro hon") ||
            normalized.contains("nang cap") || normalized.contains("cai thien")) &&
        (normalized.contains("app") || normalized.contains("ung dung")) &&
        (normalized.contains("bat dau") || normalized.contains("tu dau") ||
            normalized.contains("nen lam gi") || normalized.contains("nen uu tien"))
    if (!asksForAppDirection) return null

    return "Mình đề xuất bắt đầu bằng một đợt kiểm tra chất lượng trước khi thêm tính năng mới. " +
        "Ưu tiên theo thứ tự: (1) sửa lỗi phát nhạc và lệnh giọng nói để câu nói tự nhiên được hiểu đúng; " +
        "(2) làm giao diện gọn, dễ nhìn và thống nhất; (3) kiểm tra YouTube, phát nền và màn hình khóa; " +
        "(4) kiểm tra Radio, Google Drive và danh sách phát; (5) sau cùng mới bổ sung tính năng mới. " +
        "Bước đầu tiên hợp lý nhất là kiểm thử từng lệnh AI bằng câu nói thực tế, ghi lại câu nào không hiểu rồi sửa bộ nhận diện. " +
        "Mình chưa thay đổi bản PRO ổn định."
}

/**
 * Safe local response used only after an online request has failed.
 * It keeps short follow-up questions tied to the previous reply without pretending
 * to perform new reasoning when Gemini is unavailable or quota-limited.
 */
internal fun localConversationFallbackAfterRemoteError(
    userText: String,
    previousAssistantReply: String?,
    remoteError: String?
): String? {
    val previous = previousAssistantReply?.trim().orEmpty()
    if (previous.isBlank()) return localConversationFallback(userText, previousAssistantReply)
    // Voice/chat users often include the wake word in typed or recognized speech.
    // Normalize the same way as model routing so "AI, phân tích kỹ hơn" is still a follow-up.
    val normalized = stripNgocSiAiDirectIntentWakePrefix(
        normalizeNgocSiAiVoicePhrase(userText)
    ).removePrefix("hay ").trim()
    val exactFollowUps = setOf(
        "tai sao", "vi sao", "sao vay", "tai sao vay", "vi sao vay",
        "giai thich them", "noi ro hon", "noi ro hon di",
        "y ban la gi", "y cua ban la gi", "cu the hon", "vi du di",
        "vi du cu the", "cho vi du", "cho minh vi du", "cho toi vi du",
        "y nay la gi", "y do la gi", "phan nay la gi", "phan do la gi",
        "cho toi mot vi du", "cho minh mot vi du",
        "phan tich ky hon", "phan tich ky hon nua",
        "con ly do nao khac", "co ly do nao khac", "ly do gi nua",
        "why", "why is that", "what do you mean", "give an example"
    )
    val followUpPrefixes = listOf(
        "tai sao ", "vi sao ", "giai thich them ", "noi ro hon ",
        "cu the hon ", "vi du ", "cho vi du ", "y nay ", "y do ",
        "phan nay ", "phan do ", "con ly do nao ", "ly do gi nua ",
        "chi tiet hon ", "noi them ve ", "phan tich ky ", "explain ", "what about "
    )
    val asksForContext = normalized in exactFollowUps ||
        followUpPrefixes.any(normalized::startsWith)
    val isContinuation = normalized in setOf(
        "tiep tuc", "tiep tuc di", "tiep tuc nhe", "tiep di", "tiep nua",
        "lam tiep", "lam tiep di", "lam tiep nhe", "noi tiep", "noi tiep di",
        "noi tiep nhe", "ke tiep", "ke tiep di"
    )
    val asksForSingerComparison = listOf("so sanh", "doi chieu", "cach hat cua ca si")
        .any(normalized::contains)
    if (asksForSingerComparison) {
        buildOfflineSingerComparisonClarification(previous)?.let { return it }
    }

    val asksForBoleroAnalysis = listOf(
        "phan tich", "chi tiet hon", "noi ro hon", "vi du", "cu the hon"
    ).any(normalized::contains)
    if ((isContinuation || asksForBoleroAnalysis) &&
        buildOfflineBoleroVideoAnalysis(previous, remoteError) != null
    ) {
        return buildOfflineBoleroVideoAnalysis(previous, remoteError)
    }

    localConversationFallback(userText, previousAssistantReply)?.let { return it }
    if (!asksForContext) return null

    val previousLower = previous.lowercase(Locale.ROOT)
    val isBoleroVideoContext = previousLower.contains("bolero") ||
        previousLower.contains("video") || previousLower.contains("ca sĩ")
    val asksForDeeperAnalysis = normalized.contains("phan tich") ||
        normalized.contains("chi tiet hon") || normalized.contains("noi ro hon") ||
        normalized.contains("vi du")
    if (isBoleroVideoContext && asksForDeeperAnalysis) {
        return "Theo nội dung vừa trao đổi: “${previous.take(180)}”. Chat Gemini đang bị giới hạn hạn mức. " +
            "Mình có thể đào sâu theo hướng sản xuất video Bolero, nhưng cần nói rõ: hiện mình chỉ có phần hội thoại và mô tả ngữ cảnh, chưa đọc được từng khung hình hay lời bài hát của video đang phát. " +
            "Gợi ý phân tích cụ thể:\\n" +
            "1. Cảm xúc: xác định bài đang nghiêng về nhớ nhung, tiếc nuối hay chia xa; chọn một cảm xúc chủ đạo, tránh trộn quá nhiều sắc thái trong cùng đoạn.\\n" +
            "2. Hình ảnh: với nỗi nhớ, thử ánh đèn vàng, mưa nhẹ, con đường vắng và chuyển động máy chậm; với chia xa, dùng sân ga hoặc khung hình rộng có nhân vật nhỏ trong không gian.\\n" +
            "3. Nhịp dựng: giữ cảnh lâu ở câu hát ngân, cắt cảnh theo nhịp phách hoặc điểm rơi lời ca; tránh hiệu ứng chuyển cảnh dày làm mất chất trữ tình.\\n" +
            "4. Giọng hát: khi so sánh ca sĩ, đối chiếu độ dày và màu giọng, cách nhả chữ, luyến láy, độ rung và cách xử lý câu cao trào; không kết luận ai hay hơn nếu chưa nghe cùng một đoạn thu âm.\\n" +
            "5. Ví dụ: ở câu hát diễn tả nhớ người cũ, có thể giữ cận cảnh gương mặt và ánh mắt; đến điệp khúc, mở rộng sang bối cảnh mưa hoặc phố đêm để tăng cảm giác cô đơn.\\n" +
            "Nếu bạn gửi tên bài hát hoặc lời một đoạn, mình có thể phân tích sát nội dung hơn."
    }

    val error = remoteError.orEmpty().lowercase(Locale.ROOT)
    val status = when {
        "quota" in error || "429" in error || "han muc" in error ->
            "Chat Gemini đang bị giới hạn hạn mức"
        "network" in error || "timeout" in error || "mạng" in error ->
            "Kết nối chat trực tuyến đang gặp sự cố"
        else -> "Chat trực tuyến hiện chưa trả lời được"
    }
    return "Bạn đang hỏi tiếp về điều mình vừa nói: “${previous.take(180)}”. " +
        "$status, nên mình chưa muốn đoán lý do khi chưa đủ dữ liệu. " +
        "Bạn chỉ rõ phần muốn giải thích thêm nhé; khi chat trực tuyến hoạt động lại, mình sẽ bám đúng câu hỏi đó."
}

private fun defaultAiGreeting(isOnlineConfigured: Boolean) = AiPreviewMessage(
    false,
    if (isOnlineConfigured) {
        "Chào bạn, mình là Ngọc Sĩ AI. Cấu hình Gemini đã có; mình sẽ thử kết nối khi bạn gửi câu hỏi. Nếu máy chủ không phản hồi, mình sẽ báo rõ."
    } else {
        "Chào bạn, mình là Ngọc Sĩ AI. Mình vẫn nhận lệnh điều khiển ứng dụng; trò chuyện tự do cần kết nối AI."
    }
)

private fun loadAiPreviewMessages(
    preferences: SharedPreferences,
    isOnlineConfigured: Boolean
): List<AiPreviewMessage> {
    val stored = preferences.getString(AI_CHAT_MESSAGES_KEY, null)
        ?: return listOf(defaultAiGreeting(isOnlineConfigured))
    val decoded = runCatching {
        val array = JSONArray(stored)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val text = item.optString("text").take(MAX_SAVED_AI_MESSAGE_CHARS)
                if (text.isNotBlank()) add(AiPreviewMessage(item.optBoolean("isUser"), text))
            }
        }.takeLast(MAX_SAVED_AI_UI_MESSAGES)
    }.getOrDefault(emptyList())
    return decoded.ifEmpty { listOf(defaultAiGreeting(isOnlineConfigured)) }
}

private fun saveAiPreviewMessages(
    preferences: SharedPreferences,
    messages: List<AiPreviewMessage>
) {
    val array = JSONArray()
    messages.takeLast(MAX_SAVED_AI_UI_MESSAGES).forEach { message ->
        array.put(
            JSONObject()
                .put("isUser", message.isUser)
                .put("text", message.text.take(MAX_SAVED_AI_MESSAGE_CHARS))
        )
    }
    preferences.edit().putString(AI_CHAT_MESSAGES_KEY, array.toString()).apply()
}

private fun loadAiApiConversation(preferences: SharedPreferences): List<NgocSiAiMessage> {
    val stored = preferences.getString(AI_CHAT_CONVERSATION_KEY, null) ?: return emptyList()
    val decoded = runCatching {
        val array = JSONArray(stored)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val role = item.optString("role")
                val content = item.optString("content").trim()
                if ((role == "user" || role == "assistant") && content.isNotBlank()) {
                    add(NgocSiAiMessage(role, content.take(MAX_SAVED_AI_MESSAGE_CHARS)))
                }
            }
        }
    }.getOrDefault(emptyList())
    return restoreCompletedNgocSiAiConversation(decoded)
}

private fun saveAiApiConversation(
    preferences: SharedPreferences,
    messages: List<NgocSiAiMessage>
) {
    val bounded = messages
        .takeLast(NGOC_SI_AI_MAX_HISTORY_MESSAGES)
        .dropWhile { it.role == "assistant" }
    val array = JSONArray()
    bounded.forEach { message ->
        array.put(
            JSONObject()
                .put("role", message.role)
                .put("content", message.content.take(MAX_SAVED_AI_MESSAGE_CHARS))
        )
    }
    preferences.edit().putString(AI_CHAT_CONVERSATION_KEY, array.toString()).apply()
}

@UnstableApi
class NgocSiAiActivity : ComponentActivity() {
    // Preserve the allowlisted page context when wake-word listening is enabled.
    private val entryContext: String?
        get() {
            val page = resolveNgocSiAiEntryContext(
                intent.getStringExtra(NGOC_SI_AI_ENTRY_CONTEXT_EXTRA),
                NgocSiMainActivityState.entryContextOr()
            )
            val preferences = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
            val youtubeSourceActive = preferences.getBoolean("youtube_active_source", false)
            val activeMedia = NgocSiMainActivityState.youtubeMediaContextOrNull()
            val persistedVideoId = if (youtubeSourceActive) {
                preferences.getString("youtube_last_played_video_id", null)
            } else {
                null
            }
            return resolveNgocSiAiEntryContextForActiveYoutubePlayback(
                page,
                activeMedia?.videoId ?: persistedVideoId
                    ?: intent.getStringExtra(NGOC_SI_AI_ENTRY_MEDIA_VIDEO_ID_EXTRA),
                youtubeSourceActive
            )
        }
    private val entryMediaContextInstruction: String
        get() {
            val preferences = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
            val usePersistedYoutubeMetadata =
                preferences.getBoolean("youtube_active_source", false)
            val youtubePlayerActivityActive =
                preferences.getBoolean("youtube_player_activity_active", false)
            return buildNgocSiAiCurrentYoutubeMediaContextInstruction(
                entryContext,
                if (youtubePlayerActivityActive) null
                else NgocSiMainActivityState.youtubeMediaContextOrNull(),
                intent.getStringExtra(NGOC_SI_AI_ENTRY_MEDIA_TITLE_EXTRA)
                    ?: if (usePersistedYoutubeMetadata || youtubePlayerActivityActive) {
                        preferences.getString("youtube_last_played_title", null)
                    } else null,
                intent.getStringExtra(NGOC_SI_AI_ENTRY_MEDIA_CHANNEL_EXTRA)
                    ?: if (usePersistedYoutubeMetadata || youtubePlayerActivityActive) {
                        preferences.getString("youtube_last_played_channel", null)
                    } else null,
                preferLaunchMetadata = youtubePlayerActivityActive
            )
        }
    private val entryYoutubeVideoId: String?
        get() {
            val preferences = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
            val persistedVideoId = if (
                preferences.getBoolean("youtube_active_source", false)
            ) {
                preferences.getString("youtube_last_played_video_id", null)
            } else {
                null
            }
            val youtubePlayerActivityActive =
                preferences.getBoolean("youtube_player_activity_active", false)
            return resolveNgocSiAiCurrentYoutubeVideoId(
                entryContext,
                NgocSiMainActivityState.youtubeMediaContextOrNull()?.videoId
                    ?: persistedVideoId,
                intent.getStringExtra(NGOC_SI_AI_ENTRY_MEDIA_VIDEO_ID_EXTRA),
                preferLaunchVideoId = youtubePlayerActivityActive
            )
        }
    private var recognizedSpeech by mutableStateOf("")
    private var recognizedSpeechShouldSend by mutableStateOf(false)
    // Show live partial transcription so voice chat visibly responds while the user speaks.
    private var partialSpeechTranscript by mutableStateOf("")
    private var wakeWordListening by mutableStateOf(false)
    private var wakeWordConversationTurnId by mutableStateOf(0L)
    private var wakeWordConversationRevision by mutableStateOf(0L)
    private var wakeWordConversationQuestion by mutableStateOf("")
    private var wakeWordConversationAnswer by mutableStateOf("")
    private var ownerVoiceEnrolled by mutableStateOf(false)
    private var ownerVoiceLockEnabled by mutableStateOf(false)
    private var pendingOwnerVoiceEnrollment = false
    private var voiceRecognizer: SpeechRecognizer? = null
    private val voiceInputAudioBuffer = ByteArrayOutputStream()
    @Volatile private var pendingOwnerVoicePcm: ByteArray? = null
    private var ownerVoiceAudioSourcePipe: NgocSiVoiceAudioSourcePipe? = null
    private var voiceRecognitionInProgress by mutableStateOf(false)
    private var ownerVoiceVerificationInProgress by mutableStateOf(false)
    private var voiceRetryCount = 0
    private var activeVoiceIntent: Intent? = null
    private val voiceRetryHandler = Handler(Looper.getMainLooper())
    private var musicController: MediaController? = null
    // The Preview package is isolated from production; this optional controller lets
    // voice commands stop the active stable app player without modifying its APK.
    private var productionMusicController: MediaController? = null
    private var productionMusicControllerConnecting = false
    private var deviceQueueLoadStarted = false
    // Preserve the explicit "play" request if the device library is still loading.
    private var pendingPlayAfterQueueLoad = false
    private var pendingQueueStartIndexAfterLoad: Int? = null
    private var pendingTrackQueryAfterQueueLoad: String? = null
    private var responseTts: TextToSpeech? = null
    private var responseTtsInitialized = false
    private var responseTtsReady = false
    private var pendingVoiceReply: String? = null
    private var pendingVoiceReplyForce = false
    private var voiceRepliesEnabled by mutableStateOf(true)
    private var availableTtsVoices by mutableStateOf<List<NgocSiAiVoiceOption>>(emptyList())
    private var selectedTtsVoiceName by mutableStateOf("")
    private val wakeWordStatusHandler = Handler(Looper.getMainLooper())
    // Some Android speech services start successfully but never deliver partial/final text.
    // Recover by opening the system speech UI instead of leaving the microphone silently stuck.
    private val speechFallbackHandler = Handler(Looper.getMainLooper())
    private var speechFallbackUsed = false
    private var speechHasEnded = false
    private val speechPartialFinalizationDelayMillis = 1_800L
    private val speechNoTranscriptWatchdog = object : Runnable {
        override fun run() {
            if (!voiceRecognitionInProgress || isFinishing || isDestroyed || speechFallbackUsed) return
            val partial = partialSpeechTranscript.trim()
            if (partial.isNotBlank()) {
                // Some Android speech services emit interim text but never call onResults.
                // After a quiet timeout, submit the best available transcript instead of
                // leaving the chat stuck forever waiting for a final callback.
                speechFallbackUsed = true
                speechHasEnded = false
                speechFallbackHandler.removeCallbacks(this)
                speechFallbackHandler.removeCallbacks(speechPartialFinalizationWatchdog)
                val capturedPcm = pendingOwnerVoicePcm ?: voiceInputAudioBuffer.toByteArray()
                voiceInputAudioBuffer.reset()
                closeOwnerVoiceAudioSource()
                runCatching { voiceRecognizer?.cancel() }
                runCatching { voiceRecognizer?.destroy() }
                voiceRecognizer = null
                voiceRecognitionInProgress = false
                partialSpeechTranscript = ""
                voiceRetryCount = 0
                Toast.makeText(
                    this@NgocSiAiActivity,
                    "Đã nhận giọng nói: ${partial.take(100)}",
                    Toast.LENGTH_SHORT
                ).show()
                deliverRecognizedSpeechAfterVoiceCheck(partial, capturedPcm)
                return
            }
            speechFallbackUsed = true
            speechHasEnded = false
            speechFallbackHandler.removeCallbacks(speechPartialFinalizationWatchdog)
            val intent = activeVoiceIntent
            voiceInputAudioBuffer.reset()
            closeOwnerVoiceAudioSource()
            runCatching { voiceRecognizer?.cancel() }
            runCatching { voiceRecognizer?.destroy() }
            voiceRecognizer = null
            voiceRecognitionInProgress = false
            partialSpeechTranscript = ""
            if (NgocSiVoiceProfile.isLockEnabled(this@NgocSiAiActivity)) {
                Toast.makeText(
                    this@NgocSiAiActivity,
                    "Dịch vụ nhận dạng chưa trả về chữ cho đoạn giọng vừa thu. Hãy cập nhật ứng dụng Google hoặc Speech Services rồi thử lại.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            if (intent != null) {
                runCatching { speechLauncher.launch(intent) }
                    .onFailure {
                        Toast.makeText(
                            this@NgocSiAiActivity,
                            "Dịch vụ nghe chưa trả về chữ. Hãy cập nhật ứng dụng Google hoặc Speech Services rồi thử lại.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
        }
    }
    // If Android emits interim text but omits its final callback, submit the
    // completed utterance shortly after end-of-speech instead of waiting for the
    // general 10-second no-transcript recovery timer.
    private val speechPartialFinalizationWatchdog: Runnable = Runnable {
        if (
            !voiceRecognitionInProgress || isFinishing || isDestroyed ||
            !speechHasEnded || partialSpeechTranscript.isBlank() || speechFallbackUsed
        ) return@Runnable
        speechNoTranscriptWatchdog.run()
    }

    private val wakeWordStatusChecker = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            val wakeWordPreferences = getSharedPreferences(NgocSiWakeWordService.PREFS_NAME, MODE_PRIVATE)
            val enabled = wakeWordPreferences.getBoolean(NgocSiWakeWordService.KEY_ENABLED, false)
            val stoppedByCommand = wakeWordPreferences.getBoolean(
                NgocSiWakeWordService.KEY_STOPPED_BY_COMMAND, false
            )
            if (!enabled && stoppedByCommand) {
                wakeWordPreferences.edit()
                    .putBoolean(NgocSiWakeWordService.KEY_STOPPED_BY_COMMAND, false)
                    .apply()
            }
            val wasListening = wakeWordListening
            wakeWordListening = enabled
            val conversationPrefs = getSharedPreferences(
                NgocSiWakeWordService.CONVERSATION_PREFS_NAME,
                MODE_PRIVATE
            )
            val conversationRevision = conversationPrefs.getLong(
                NgocSiWakeWordService.KEY_CONVERSATION_REVISION, 0L
            )
            if (conversationRevision > wakeWordConversationRevision) {
                wakeWordConversationTurnId = conversationPrefs.getLong(
                    NgocSiWakeWordService.KEY_CONVERSATION_TURN_ID, 0L
                )
                wakeWordConversationRevision = conversationRevision
                wakeWordConversationQuestion = conversationPrefs.getString(
                    NgocSiWakeWordService.KEY_CONVERSATION_QUESTION, ""
                ).orEmpty()
                wakeWordConversationAnswer = conversationPrefs.getString(
                    NgocSiWakeWordService.KEY_CONVERSATION_ANSWER, ""
                ).orEmpty()
            }
            ownerVoiceEnrolled = NgocSiVoiceProfile.hasProfile(this@NgocSiAiActivity)
            ownerVoiceLockEnabled = NgocSiVoiceProfile.isLockEnabled(this@NgocSiAiActivity)
            if (enabled) {
                wakeWordStatusHandler.postDelayed(this, 1_000L)
            } else if (
                shouldShowNgocSiWakeWordStoppedWarning(
                    wasListening = wasListening,
                    stoppedByCommand = stoppedByCommand
                )
            ) {
                Toast.makeText(
                    this@NgocSiAiActivity,
                    "Nghe từ khóa đã tự tắt. Máy có thể chưa có gói nhận dạng tiếng Việt offline; hãy dùng nút micro để ra lệnh.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private val inAppSpeechListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    voiceRecognitionInProgress = true
                    partialSpeechTranscript = ""
                }
            }
        }

        override fun onBeginningOfSpeech() {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    speechHasEnded = false
                    partialSpeechTranscript = ""
                    speechFallbackHandler.removeCallbacks(speechPartialFinalizationWatchdog)
                    // Give the direct recognition service a chance, then fall back if it
                    // produces no transcript at all. This watchdog never submits speech itself.
                    speechFallbackHandler.removeCallbacks(speechNoTranscriptWatchdog)
                    speechFallbackHandler.postDelayed(speechNoTranscriptWatchdog, 10_000L)
                }
            }
        }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) {
            if (buffer == null || isFinishing || isDestroyed) return
            val remaining = 160_000 - voiceInputAudioBuffer.size()
            if (remaining > 0) voiceInputAudioBuffer.write(buffer, 0, minOf(buffer.size, remaining))
        }
        override fun onEndOfSpeech() {
            runOnUiThread {
                if (!isFinishing && !isDestroyed && voiceRecognitionInProgress) {
                    speechHasEnded = true
                    speechFallbackHandler.removeCallbacks(speechPartialFinalizationWatchdog)
                    speechFallbackHandler.postDelayed(
                        speechPartialFinalizationWatchdog,
                        speechPartialFinalizationDelayMillis
                    )
                }
            }
        }

        override fun onError(error: Int) {
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    voiceRecognitionInProgress = false
                    partialSpeechTranscript = ""
                    return@runOnUiThread
                }
                speechFallbackHandler.removeCallbacks(speechNoTranscriptWatchdog)
                speechFallbackHandler.removeCallbacks(speechPartialFinalizationWatchdog)
                speechHasEnded = false
                val partial = partialSpeechTranscript.trim()
                val capturedPcm = pendingOwnerVoicePcm ?: voiceInputAudioBuffer.toByteArray()
                if (partial.isNotBlank() &&
                    (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                ) {
                    voiceInputAudioBuffer.reset()
                    closeOwnerVoiceAudioSource()
                    runCatching { voiceRecognizer?.cancel() }
                    voiceRecognitionInProgress = false
                    partialSpeechTranscript = ""
                    voiceRetryCount = 0
                    Toast.makeText(
                        this@NgocSiAiActivity,
                        "Đang gửi phần câu nói đã nhận được…",
                        Toast.LENGTH_SHORT
                    ).show()
                    deliverRecognizedSpeechAfterVoiceCheck(partial, capturedPcm)
                    return@runOnUiThread
                }
                closeOwnerVoiceAudioSource()
                partialSpeechTranscript = ""
                val canRetry = error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                if (canRetry && voiceRetryCount < 1 && activeVoiceIntent != null) {
                    voiceRetryCount += 1
                    voiceRetryHandler.postDelayed({
                        if (isFinishing || isDestroyed) {
                            voiceRecognitionInProgress = false
                            return@postDelayed
                        }
                        val recognizer = voiceRecognizer
                        val retryIntent = activeVoiceIntent
                        if (recognizer != null && retryIntent != null) {
                            voiceInputAudioBuffer.reset()
                            runCatching { recognizer.startListening(retryIntent) }
                                .onSuccess {
                                    speechFallbackHandler.postDelayed(speechNoTranscriptWatchdog, 10_000L)
                                }
                                .onFailure {
                                    voiceRecognitionInProgress = false
                                    partialSpeechTranscript = ""
                                    Toast.makeText(
                                        this@NgocSiAiActivity,
                                        "Chưa nghe rõ. Hãy nhấn micro và nói gần điện thoại hơn.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                        } else {
                            voiceRecognitionInProgress = false
                        }
                    }, 250L)
                    return@runOnUiThread
                }
                voiceRecognitionInProgress = false
                partialSpeechTranscript = ""
                val message = when (error) {
                    SpeechRecognizer.ERROR_AUDIO ->
                        "Không thu được âm thanh. Hãy kiểm tra micro và thử lại."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "Ứng dụng chưa được cấp quyền micro. Hãy cho phép quyền Micro trong Cài đặt."
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER ->
                        "Dịch vụ nhận dạng tiếng Việt không kết nối được. Hãy kiểm tra Internet hoặc thử lại."
                    SpeechRecognizer.ERROR_NO_MATCH ->
                        "Chưa nghe rõ câu lệnh. Hãy nói gần micro, rõ ràng rồi nhấn micro thử lại."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "Chưa phát hiện tiếng nói. Hãy nhấn micro rồi bắt đầu nói ngay."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                        "Dịch vụ nhận dạng đang bận. Đợi một chút rồi thử lại."
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        "Dịch vụ nhận dạng trên máy chưa hỗ trợ tiếng Việt. Hãy cập nhật ứng dụng Google hoặc Speech Services."
                    else ->
                        "Nhận dạng giọng nói gặp lỗi (mã $error). Hãy thử lại hoặc cập nhật dịch vụ Google."
                }
                Toast.makeText(this@NgocSiAiActivity, message, Toast.LENGTH_LONG).show()
            }
        }

        override fun onResults(results: Bundle?) {
            val candidates = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
                .map { it.trim() }
                .filter { it.isNotBlank() }
            val capturedPcm = pendingOwnerVoicePcm ?: voiceInputAudioBuffer.toByteArray()
            voiceInputAudioBuffer.reset()
            closeOwnerVoiceAudioSource()
            // Prefer a recognized candidate that matches an implemented music command;
            // otherwise keep Android's highest-confidence candidate.
            val phrase = candidates.firstOrNull { isNgocSiAiExplicitStopPhrase(it) }
                ?: candidates.firstOrNull { classifyNgocSiAiLocalCommand(it, entryContext) != null }
                ?: candidates.firstOrNull().orEmpty()
            runOnUiThread {
                speechFallbackHandler.removeCallbacks(speechNoTranscriptWatchdog)
                speechFallbackHandler.removeCallbacks(speechPartialFinalizationWatchdog)
                speechHasEnded = false
                voiceRecognitionInProgress = false
                partialSpeechTranscript = ""
                voiceRetryCount = 0
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (phrase.isBlank()) {
                    Toast.makeText(
                        this@NgocSiAiActivity,
                        "Chưa nhận được câu lệnh. Hãy nhấn micro và nói lại.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this@NgocSiAiActivity,
                        "Đã nhận giọng nói: ${phrase.take(100)}",
                        Toast.LENGTH_SHORT
                    ).show()
                    // Verify the speaker off the UI thread before routing to any command or AI request.
                    deliverRecognizedSpeechAfterVoiceCheck(phrase, capturedPcm)
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            // Partial results are only a live preview. Never execute a command or send
            // chat to Gemini until onResults returns the complete utterance.
            val candidates = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
                .map { it.trim() }
                .filter { it.isNotBlank() }
            val phrase = candidates.firstOrNull { isNgocSiAiExplicitStopPhrase(it) }
                ?: candidates.firstOrNull { classifyNgocSiAiLocalCommand(it, entryContext) != null }
                ?: candidates.firstOrNull().orEmpty()
            runOnUiThread {
                if (!isFinishing && !isDestroyed && voiceRecognitionInProgress) {
                    partialSpeechTranscript = phrase.take(180)
                    if (phrase.isNotBlank()) {
                        // Keep the no-transcript recovery active while the user is speaking.
                        speechFallbackHandler.removeCallbacks(speechNoTranscriptWatchdog)
                        speechFallbackHandler.postDelayed(speechNoTranscriptWatchdog, 10_000L)
                        // Some Android services emit their last interim result just after
                        // onEndOfSpeech; restart the short fallback so that phrase is not lost.
                        if (speechHasEnded) {
                            speechFallbackHandler.removeCallbacks(speechPartialFinalizationWatchdog)
                            speechFallbackHandler.postDelayed(
                                speechPartialFinalizationWatchdog,
                                speechPartialFinalizationDelayMillis
                            )
                        }
                    }
                }
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private val speechLauncher: androidx.activity.result.ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            speechFallbackHandler.removeCallbacks(speechNoTranscriptWatchdog)
            speechFallbackHandler.removeCallbacks(speechPartialFinalizationWatchdog)
            speechHasEnded = false
            speechFallbackUsed = false
            voiceRecognitionInProgress = false
            partialSpeechTranscript = ""
            if (NgocSiVoiceProfile.isLockEnabled(this)) {
                Toast.makeText(
                    this,
                    "Chế độ nhận dạng dự phòng không cung cấp âm thanh để xác minh giọng. Câu nói đã bị bỏ qua.",
                    Toast.LENGTH_LONG
                ).show()
                return@registerForActivityResult
            }
            val candidates = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                .orEmpty()
            // If the system speech UI was used as a fallback, accept ordinary
            // conversational speech too—not only recognized local music commands.
            // Otherwise voice chat appears to do nothing for normal questions.
            val phrase = candidates.firstOrNull { isNgocSiAiExplicitStopPhrase(it) }
                ?: selectNgocSiAiLocalCommandCandidate(candidates, entryContext)
                ?: candidates.firstOrNull().orEmpty().trim()
            if (result.resultCode == RESULT_OK && phrase.isNotBlank()) {
                // Confirm recognition immediately so a user can distinguish microphone/
                // speech-service problems from command-routing or AI-backend problems.
                Toast.makeText(
                    this,
                    "Đã nhận giọng nói: ${phrase.take(100)}",
                    Toast.LENGTH_SHORT
                ).show()
                // Route all recognized speech through the same allowlisted command/chat handler.
                // Local playback commands work without Firebase; other speech gets an explicit
                // explanation if online AI has not been configured.
                recognizedSpeech = phrase
                recognizedSpeechShouldSend = true
            } else {
                val audioPermissionGranted = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                val message = when {
                    !audioPermissionGranted ->
                        "Chưa có quyền micro. Hãy vào Cài đặt > Ứng dụng > NGỌC SĨ AI Preview > Quyền > Micro và chọn Cho phép."
                    result.resultCode == RESULT_CANCELED ->
                        "Chưa nhận được câu nói. Hãy nhấn micro, nói rõ sau tiếng bíp và thử lại. Nếu không mở được nhận dạng giọng nói, hãy cập nhật ứng dụng Google và dịch vụ Speech Services."
                    else ->
                        "Không nhận dạng được giọng nói. Hãy kiểm tra quyền micro, kết nối mạng và thử lại."
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }

    private val voiceInputPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startVoiceInput() else Toast.makeText(
                this,
                "Cần quyền micro để nhận lệnh giọng nói. Hãy cấp quyền rồi nhấn micro lần nữa.",
                Toast.LENGTH_LONG
            ).show()
        }

    private val musicPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                loadDeviceMusicIntoQueue()
            } else {
                Toast.makeText(
                    this,
                    "Chưa cấp quyền đọc nhạc trên thiết bị. Chat AI vẫn dùng được; lệnh phát nhạc cần quyền này.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val wakeWordPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (pendingOwnerVoiceEnrollment) {
                pendingOwnerVoiceEnrollment = false
                if (granted) requestOwnerVoiceEnrollment()
                else Toast.makeText(this, "Cần quyền micro để đăng ký giọng nói.", Toast.LENGTH_LONG).show()
            } else if (granted) {
                try {
                    NgocSiWakeWordService.start(
                        this,
                        entryContext,
                        youtubeVideoId = entryYoutubeVideoId,
                        mediaTitle = intent.getStringExtra(NGOC_SI_AI_ENTRY_MEDIA_TITLE_EXTRA),
                        mediaChannel = intent.getStringExtra(NGOC_SI_AI_ENTRY_MEDIA_CHANNEL_EXTRA)
                    )
                    wakeWordListening = true
                    monitorWakeWordStatus()
                } catch (_: Exception) {
                    Toast.makeText(this, "Không thể bật nghe từ khóa. Hãy thử lại.", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "Cần quyền micro để nghe từ khóa AI.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onResume() {
        super.onResume()
        refreshOwnerVoiceState()
        refreshTtsVoiceOptions()
        wakeWordListening = getSharedPreferences(NgocSiWakeWordService.PREFS_NAME, MODE_PRIVATE)
            .getBoolean(NgocSiWakeWordService.KEY_ENABLED, false)
        if (wakeWordListening) monitorWakeWordStatus()
    }

    private fun refreshTtsVoiceOptions() {
        val tts = responseTts ?: return
        availableTtsVoices = NgocSiAiVoiceSettings.availableOptions(tts.voices.orEmpty())
        selectedTtsVoiceName = NgocSiAiVoiceSettings.selectedVoiceName(this)
            ?.takeIf { saved -> availableTtsVoices.any { it.name == saved } }
            ?: tts.voice?.name.orEmpty()
    }

    private fun selectAiVoice(name: String) {
        val tts = responseTts
        val voice = tts?.voices?.firstOrNull { it.name == name }
        if (tts == null || voice == null) {
            Toast.makeText(this, "Không tìm thấy giọng đọc này trên điện thoại.", Toast.LENGTH_LONG).show()
            refreshTtsVoiceOptions()
            return
        }
        val result = runCatching { tts.setVoice(voice) }.getOrDefault(TextToSpeech.ERROR)
        if (result != TextToSpeech.SUCCESS) {
            Toast.makeText(this, "Không đổi được giọng đọc. Hãy thử một giọng khác.", Toast.LENGTH_LONG).show()
            return
        }
        NgocSiAiVoiceSettings.saveSelectedVoiceName(this, name)
        selectedTtsVoiceName = name
        responseTtsReady = true
        if (wakeWordListening) NgocSiWakeWordService.updateVoice(this)
        // Play a short preview so the user can compare installed voices.
        speakVoiceReply("Xin chào, tôi là Ngọc Sĩ AI. Đây là giọng đọc bạn vừa chọn.", force = true)
    }

    private fun manageTtsVoices() {
        runCatching {
            startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
        }.onFailure {
            Toast.makeText(
                this,
                "Hãy mở Cài đặt điện thoại, tìm mục Văn bản thành giọng nói và tải thêm giọng đọc.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun monitorWakeWordStatus() {
        wakeWordStatusHandler.removeCallbacks(wakeWordStatusChecker)
        wakeWordStatusHandler.postDelayed(wakeWordStatusChecker, 1_000L)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val aiEntryContext = entryContext
        // Prefer the page that opened this AI screen; MainActivity will keep it current
        // as the user navigates after the AI UI has been closed.
        NgocSiMainActivityState.updateEntryContext(aiEntryContext)
        refreshOwnerVoiceState()
        responseTts = TextToSpeech(this) { status ->
            responseTtsInitialized = true
            if (status == TextToSpeech.SUCCESS) {
                val tts = responseTts
                if (tts != null) {
                    // TTS errors can arrive asynchronously after speak() returned SUCCESS.
                    // Surface them to the user instead of silently dropping spoken replies.
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) = Unit

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) {
                                    Toast.makeText(
                                        this@NgocSiAiActivity,
                                        "Điện thoại không phát được giọng đọc. Hãy kiểm tra cài đặt Văn bản thành giọng nói và âm lượng.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) {
                                    Toast.makeText(
                                        this@NgocSiAiActivity,
                                        "Lỗi giọng đọc (mã $errorCode). Hãy kiểm tra dữ liệu tiếng Việt trong cài đặt Văn bản thành giọng nói.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    })
                    val vietnameseResult = runCatching {
                        tts.setLanguage(Locale.forLanguageTag("vi-VN"))
                    }.getOrDefault(TextToSpeech.LANG_MISSING_DATA)
                    responseTtsReady = vietnameseResult >= TextToSpeech.LANG_AVAILABLE
                    if (!responseTtsReady) {
                        val fallbackResult = runCatching {
                            tts.setLanguage(Locale.getDefault())
                        }.getOrDefault(TextToSpeech.LANG_MISSING_DATA)
                        responseTtsReady = fallbackResult >= TextToSpeech.LANG_AVAILABLE
                    }
                    // Apply the chosen engine voice after selecting a supported language.
                    selectedTtsVoiceName = NgocSiAiVoiceSettings.applySavedVoice(this@NgocSiAiActivity, tts)
                        ?: tts.voice?.name.orEmpty()
                    availableTtsVoices = NgocSiAiVoiceSettings.availableOptions(tts.voices.orEmpty())
                }
            }
            val queuedReply = pendingVoiceReply
            if (queuedReply != null) {
                val forceQueuedReply = pendingVoiceReplyForce
                pendingVoiceReply = null
                pendingVoiceReplyForce = false
                if (responseTtsReady) {
                    speakVoiceReply(queuedReply, force = forceQueuedReply)
                } else {
                    Toast.makeText(
                        this,
                        "Chưa có giọng đọc khả dụng. Hãy vào cài đặt Văn bản thành giọng nói và tải dữ liệu tiếng Việt.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        val aiClient by lazy(LazyThreadSafetyMode.NONE) { NgocSiAiRemoteClient(applicationContext) }
        val remoteConfigured = BuildConfig.FIREBASE_API_KEY.isNotBlank() &&
            BuildConfig.FIREBASE_PROJECT_ID.isNotBlank() &&
            BuildConfig.FIREBASE_SENDER_ID.isNotBlank() &&
            BuildConfig.FIREBASE_APP_ID.isNotBlank()
        val aiChatPreferences = getSharedPreferences(AI_CHAT_PREFS_NAME, MODE_PRIVATE)
        wakeWordListening = getSharedPreferences(NgocSiWakeWordService.PREFS_NAME, MODE_PRIVATE)
            .getBoolean(NgocSiWakeWordService.KEY_ENABLED, false)
        voiceRepliesEnabled = getSharedPreferences(AI_CHAT_PREFS_NAME, MODE_PRIVATE)
            .getBoolean(AI_VOICE_REPLY_KEY, true)
        connectMusicController()
        connectProductionMusicController()
        setContent {
            NgocSiAiPreviewScreen(
                isOnlineConfigured = remoteConfigured,
                entryContext = aiEntryContext,
                chatPreferences = aiChatPreferences,
                onIsLocalModelInstalled = {
                    NgocSiAiOnDeviceModel.isInstalled(applicationContext)
                },
                onDownloadLocalModel = { onProgress ->
                    NgocSiAiOnDeviceModel.download(applicationContext) { progress ->
                        onProgress(progress.downloadedBytes, progress.totalBytes)
                    }
                },
                onGenerateLocalModel = { prompt, priorMessages ->
                    val memories = decodeNgocSiAiMemory(
                        aiChatPreferences.getString(NGOC_SI_AI_LONG_TERM_MEMORY_KEY, null)
                    )
                    NgocSiAiOnDeviceModel.generate(
                        applicationContext,
                        prompt,
                        memories,
                        priorMessages,
                        entryContext = aiEntryContext,
                        mediaContextInstruction = entryMediaContextInstruction
                    )
                },
                onReleaseLocalModel = {
                    NgocSiAiOnDeviceModel.release()
                },
                onLocalModelBackendStatus = {
                    NgocSiAiOnDeviceModel.backendStatusLabel()
                },
                onSendOnline = { history, executeTool, onPartialResponse ->
                    aiClient.send(
                        history,
                        executeTool,
                        entryContext = entryContext,
                        mediaContextInstruction = entryMediaContextInstruction,
                        youtubeVideoId = entryYoutubeVideoId,
                        onPartialResponse = onPartialResponse
                    )
                },
                onExecuteAppCommand = { action, query -> executeAiAppCommand(action, query) },
                onLocalCommand = { command, phrase -> runLocalCommand(command, phrase) },
                onVoiceReply = { message -> speakVoiceReply(message) },
                onSpeakMessage = { message -> speakVoiceReply(message, force = true) },
                voiceRepliesEnabled = voiceRepliesEnabled,
                onToggleVoiceReplies = { toggleVoiceReplies() },
                voiceOptions = availableTtsVoices,
                selectedVoiceName = selectedTtsVoiceName,
                onVoiceSelected = { name -> selectAiVoice(name) },
                onManageVoices = { manageTtsVoices() },
                recognizedSpeech = recognizedSpeech,
                recognizedSpeechShouldSend = recognizedSpeechShouldSend,
                partialSpeechTranscript = partialSpeechTranscript,
                isVoiceListening = voiceRecognitionInProgress,
                onSpeechConsumed = {
                    recognizedSpeech = ""
                    recognizedSpeechShouldSend = false
                    partialSpeechTranscript = ""
                },
                onBack = { finish() },
                onVoice = { startVoiceInput() },
                isWakeWordListening = wakeWordListening,
                onToggleWakeWord = { toggleWakeWordListening(aiEntryContext) },
                wakeWordConversationTurnId = wakeWordConversationTurnId,
                wakeWordConversationRevision = wakeWordConversationRevision,
                wakeWordConversationQuestion = wakeWordConversationQuestion,
                wakeWordConversationAnswer = wakeWordConversationAnswer,
            )
        }
        requestMusicPermissionIfNeeded()
    }

    private fun requestMusicPermissionIfNeeded() {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            loadDeviceMusicIntoQueue()
        } else {
            musicPermissionLauncher.launch(permission)
        }
    }

    /**
     * Loads a queue only into this isolated Preview app's own Media3 service.
     * It never modifies or controls the production NGỌC SĨ MUSIC package.
     */
    private fun loadDeviceMusicIntoQueue() {
        if (deviceQueueLoadStarted) return
        val controller = musicController ?: return
        if (controller.mediaItemCount > 0) return

        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        deviceQueueLoadStarted = true
        lifecycleScope.launch {
            try {
                val localItems = withContext(Dispatchers.IO) { queryDeviceMusicItems() }
                if (isFinishing || isDestroyed) {
                    deviceQueueLoadStarted = false
                    return@launch
                }

                val activeController = musicController
                if (activeController == null || activeController.mediaItemCount > 0) {
                    deviceQueueLoadStarted = false
                    return@launch
                }
                if (localItems.isEmpty()) {
                    deviceQueueLoadStarted = false
                    pendingPlayAfterQueueLoad = false
                    pendingQueueStartIndexAfterLoad = null
                    pendingTrackQueryAfterQueueLoad = null
                    Toast.makeText(
                        this@NgocSiAiActivity,
                        "Không tìm thấy tệp nhạc cục bộ. Bạn vẫn có thể dùng chat nếu AI trực tuyến đã cấu hình.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                activeController.setMediaItems(localItems)
                activeController.prepare()
                deviceQueueLoadStarted = false
                val pendingTrackQuery = pendingTrackQueryAfterQueueLoad
                pendingTrackQueryAfterQueueLoad = null
                val targetTrackIndex = pendingTrackQuery?.let { query ->
                    val normalizedQuery = normalizeNgocSiAiVoicePhrase(query)
                    val exact = localItems.indexOfFirst { item ->
                        normalizeNgocSiAiVoicePhrase(item.mediaMetadata.title?.toString().orEmpty()) == normalizedQuery
                    }
                    if (exact >= 0) exact else localItems.indexOfFirst { item ->
                        val title = normalizeNgocSiAiVoicePhrase(item.mediaMetadata.title?.toString().orEmpty())
                        val artist = normalizeNgocSiAiVoicePhrase(item.mediaMetadata.artist?.toString().orEmpty())
                        title.contains(normalizedQuery) || artist.contains(normalizedQuery)
                    }.takeIf { it >= 0 }
                }
                val shouldStartPlayback = if (pendingTrackQuery != null) {
                    targetTrackIndex != null
                } else {
                    pendingPlayAfterQueueLoad
                }
                val requestedStartIndex = targetTrackIndex ?: pendingQueueStartIndexAfterLoad ?: 0
                pendingPlayAfterQueueLoad = false
                pendingQueueStartIndexAfterLoad = null
                val canStartPlayback = shouldStartPlayback && requestedStartIndex in localItems.indices
                if (canStartPlayback) {
                    activeController.seekTo(requestedStartIndex, 0L)
                    activeController.play()
                }
                val startedTitle = localItems.getOrNull(requestedStartIndex)?.mediaMetadata?.title?.toString().orEmpty()
                Toast.makeText(
                    this@NgocSiAiActivity,
                    when {
                        targetTrackIndex != null && canStartPlayback -> "Đã nạp thư viện và đang phát: $startedTitle."
                        targetTrackIndex == null && canStartPlayback && requestedStartIndex == 1 -> "Đã nạp ${localItems.size} bài và đang phát bài thứ hai."
                        targetTrackIndex == null && canStartPlayback -> "Đã nạp ${localItems.size} bài và bắt đầu phát nhạc."
                        pendingTrackQuery != null -> "Đã nạp thư viện nhưng không tìm thấy bài “$pendingTrackQuery”."
                        shouldStartPlayback -> "Đã nạp thư viện nhưng danh sách chưa có bài thứ hai để phát."
                        else -> "Đã nạp ${localItems.size} bài nhạc trên điện thoại."
                    },
                    Toast.LENGTH_LONG
                ).show()
            } catch (cancelled: CancellationException) {
                deviceQueueLoadStarted = false
                throw cancelled
            } catch (_: SecurityException) {
                deviceQueueLoadStarted = false
                pendingPlayAfterQueueLoad = false
                pendingQueueStartIndexAfterLoad = null
                pendingTrackQueryAfterQueueLoad = null
                Toast.makeText(
                    this@NgocSiAiActivity,
                    "Android chưa cho phép đọc thư viện nhạc. Hãy cấp quyền rồi mở lại NGỌC SĨ AI.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (_: Exception) {
                deviceQueueLoadStarted = false
                pendingPlayAfterQueueLoad = false
                pendingQueueStartIndexAfterLoad = null
                pendingTrackQueryAfterQueueLoad = null
                Toast.makeText(
                    this@NgocSiAiActivity,
                    "Không tải được thư viện nhạc trên máy. Hãy thử lại.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun queryDeviceMusicItems(): List<MediaItem> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        val items = mutableListOf<MediaItem>()

        contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.equals("<unknown>", ignoreCase = true) }
                    ?: "Không có tên"
                val artist = cursor.getString(artistColumn)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.equals("<unknown>", ignoreCase = true) }
                    ?: "Nghệ sĩ không rõ"
                val album = cursor.getString(albumColumn)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.equals("<unknown>", ignoreCase = true) }
                    .orEmpty()
                val uri = ContentUris.withAppendedId(collection, id)
                val metadata = MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .build()
                items += MediaItem.Builder()
                    .setMediaId(uri.toString())
                    .setUri(uri)
                    .setMediaMetadata(metadata)
                    .build()
            }
        }
        return items
    }

    private fun connectMusicController() {
        val token = SessionToken(this, ComponentName(this, MusicService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            runCatching { future.get() }
                .onSuccess { connectedController ->
                    if (isFinishing || isDestroyed) {
                        connectedController.release()
                    } else {
                        musicController = connectedController
                        loadDeviceMusicIntoQueue()
                    }
                }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun connectProductionMusicController() {
        if (productionMusicController != null || productionMusicControllerConnecting) return
        productionMusicControllerConnecting = true
        val component = ComponentName("com.ngocsi.music", "com.ngocsi.music.MusicService")
        val future = runCatching {
            MediaController.Builder(this, SessionToken(this, component)).buildAsync()
        }.getOrElse {
            productionMusicControllerConnecting = false
            return
        }
        future.addListener({
            productionMusicControllerConnecting = false
            runCatching { future.get() }
                .onSuccess { connectedController ->
                    if (isFinishing || isDestroyed) {
                        connectedController.release()
                    } else {
                        productionMusicController = connectedController
                    }
                }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun hasActivePlayback(controller: MediaController?): Boolean =
        controller != null && (
            controller.isPlaying ||
                (controller.playWhenReady &&
                    controller.playbackState == androidx.media3.common.Player.STATE_BUFFERING)
            )

    private fun preferredPlaybackController(): MediaController? {
        val production = productionMusicController
        val preview = musicController
        return when (
            selectNgocSiAiPlaybackTarget(
                productionConnected = production != null,
                productionActive = hasActivePlayback(production),
                productionHasItems = (production?.mediaItemCount ?: 0) > 0,
                previewConnected = preview != null,
                previewActive = hasActivePlayback(preview),
                previewHasItems = (preview?.mediaItemCount ?: 0) > 0
            )
        ) {
            NgocSiAiPlaybackTarget.PRODUCTION -> production
            NgocSiAiPlaybackTarget.PREVIEW -> preview
            null -> null
        }
    }

    private fun stopActiveMusicFromAi(): String {
        val production = productionMusicController
        val preview = musicController

        // Never stop an idle Preview queue as a false substitute for the stable player's
        // session. If the stable session is unavailable, report that stopping was not done.
        if (production == null && productionMusicControllerConnecting) {
            return "Đang kết nối trình phát NGỌC SĨ MUSIC. Nhạc chưa được xác nhận là đã dừng."
        }

        val target = when (
            selectNgocSiAiStopPlaybackTarget(
                productionConnected = production != null,
                productionActive = hasActivePlayback(production),
                previewConnected = preview != null,
                previewActive = hasActivePlayback(preview)
            )
        ) {
            NgocSiAiPlaybackTarget.PRODUCTION -> production
            NgocSiAiPlaybackTarget.PREVIEW -> preview
            null -> null
        } ?: run {
            connectProductionMusicController()
            return "Chưa kết nối được trình phát NGỌC SĨ MUSIC; nhạc chưa được dừng. Hãy mở ứng dụng chính rồi thử lại."
        }

        return runCatching {
            target.pause()
            target.playWhenReady = false
            target.stop()
            if (target === production) {
                "Đã dừng hẳn nhạc đang phát trong NGỌC SĨ MUSIC."
            } else {
                "Đã dừng hẳn nhạc đang phát trong NGỌC SĨ AI Preview."
            }
        }.getOrElse {
            "Không thể dừng nhạc lúc này. Nhạc chưa được xác nhận là đã dừng; hãy thử lại."
        }
    }

    private fun runYouTubeControl(action: String, response: String): String {
        if (action == "current_track") return response
        val prefs = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
        val hasSavedVideo = !prefs.getString("youtube_last_played_video_id", null).isNullOrBlank()
        return when (
            selectNgocSiAiYouTubeDispatchTarget(
                hasSavedVideo = hasSavedVideo,
                liveControlHostAvailable = YouTubePlayerActivity.hasLiveInstance() ||
                    NgocSiMainActivityState.hasLiveInstance()
            )
        ) {
            NgocSiAiYouTubeDispatchTarget.OPEN_YOUTUBE -> openAppDestination(
                "youtube",
                "Chưa có video YouTube gần đây để điều khiển. Tôi đã mở YouTube; hãy tìm và phát video trước."
            )

            NgocSiAiYouTubeDispatchTarget.LIVE_CONTROL_HOST -> {
                // Broadcast only when the Activity is known to exist in this process.
                // A stale SharedPreferences flag is not proof that its receiver survived
                // background process reclamation.
                val sent = runCatching {
                    sendBroadcast(
                        Intent(YouTubeBackgroundService.ACTION_CONTROL)
                            .setPackage(packageName)
                            .putExtra(YouTubeBackgroundService.EXTRA_CONTROL, action)
                    )
                }.isSuccess
                if (sent) response
                else openAppDestination("youtube_command", response, youtubeAction = action)
            }

            NgocSiAiYouTubeDispatchTarget.MAIN_INLINE_PLAYER ->
                openAppDestination("youtube_command", response, youtubeAction = action)
        }
    }

    private fun youtubeControlResponse(action: String): String = when (action) {
        "current_track" -> {
            val prefs = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
            val title = prefs.getString("youtube_last_played_title", null)
                ?.takeIf { it.isNotBlank() } ?: "Chưa rõ tên video"
            val channel = prefs.getString("youtube_last_played_channel", null)
                ?.takeIf { it.isNotBlank() }
            val status = if (prefs.getBoolean("youtube_playback_is_playing", false)) {
                "Đang phát"
            } else {
                "Đang tạm dừng hoặc chưa phát"
            }
            if (channel.isNullOrBlank()) "$status: $title" else "$status: $title — $channel"
        }
        "play" -> "Đang phát tiếp video YouTube."
        "pause" -> "Đang tạm dừng video YouTube."
        "stop" -> "Đang dừng video YouTube."
        "next" -> "Đang chuyển sang video YouTube tiếp theo."
        "previous" -> "Đang chuyển về video YouTube trước."
        "seek_forward_10" -> "Đang tua video YouTube tới 10 giây."
        "seek_back_10" -> "Đang tua video YouTube lùi 10 giây."
        "shuffle_on" -> "Đang bật phát ngẫu nhiên YouTube."
        "shuffle_off" -> "Đang tắt phát ngẫu nhiên YouTube."
        "repeat_off" -> "Đang tắt chế độ lặp YouTube."
        "repeat_all" -> "Đang bật lặp hàng đợi YouTube."
        "repeat_one" -> "Đang bật lặp video YouTube."
        "favorite" -> "Đang lưu video YouTube vào Yêu thích."
        "watch_later" -> "Đang lưu video YouTube vào Xem sau."
        else -> "Đã gửi lệnh điều khiển YouTube."
    }

    /**
     * Executes only actions converted by the shared allowlist mapper. The model cannot
     * call arbitrary Activity methods, launch arbitrary Intents, or mutate app data directly.
     */
    private fun executeAiAppCommand(action: String, query: String): NgocSiAiToolResult {
        val route = resolveNgocSiAiToolCommand(
            action = action,
            query = query,
            requireActivatedSession = false,
            entryContext = entryContext
        ) ?: return NgocSiAiToolResult(
            routed = false,
            feedback = "Thiếu thông tin hoặc action không nằm trong danh sách điều khiển được phép. Chưa thực hiện thao tác."
        )
        val phrase = route.phrase
        val command = route.command
        // Let exceptions propagate to executeNgocSiAiToolSafely in the remote
        // agent. That single boundary preserves CancellationException and converts
        // ordinary failures into safe feedback without swallowing coroutine cancellation.
        return NgocSiAiToolResult(
            routed = true,
            feedback = runLocalCommand(command, phrase)
        )
    }

    private fun dispatchMainActivityTransport(command: NgocSiAiLocalCommand): String? {
        val action = ngocSiAiMainActivityTransportAction(command) ?: return null
        if (!NgocSiMainActivityState.hasLiveInstance()) return null
        val sent = runCatching {
            sendBroadcast(
                Intent(YouTubeBackgroundService.ACTION_CONTROL)
                    .setPackage(packageName)
                    .putExtra(YouTubeBackgroundService.EXTRA_CONTROL, action)
            )
        }.isSuccess
        return if (sent) ngocSiAiMainActivityTransportResponse(command) else null
    }

    private fun runLocalCommand(command: NgocSiAiLocalCommand, originalText: String = ""): String {
        val prefs = getSharedPreferences("ngoc_si_music", MODE_PRIVATE)
        val youtubeAction = ngocSiAiYouTubeActionForCommand(
            command,
            youtubeActiveSource = prefs.getBoolean("youtube_active_source", false)
        )
        if (youtubeAction != null) {
            return runYouTubeControl(youtubeAction, youtubeControlResponse(youtubeAction))
        }
        dispatchMainActivityTransport(command)?.let { return it }
        executeNgocSiAiSystemVolumeCommand(
            getSystemService(AudioManager::class.java),
            command
        )?.let { return it }

        when (command) {
            NgocSiAiLocalCommand.HELP -> return "Tôi có thể điều khiển nhạc trên điện thoại, Drive, Radio và YouTube: phát/tạm dừng/dừng, chuyển bài, phát bài theo tên, thêm bài vào hàng đợi, tua, chỉnh tốc độ và âm lượng; bật ngẫu nhiên/lặp; tìm nội dung YouTube, Online và Drive; mở Thư viện, Yêu thích, Album, Thư mục, Radio, TV và Drive; mở/phát playlist, thêm bài hoặc hàng đợi vào playlist; lưu bài hiện tại vào Yêu thích; mở Hàng đợi, Trình phát, Cài đặt và hẹn giờ. Một số thao tác phụ thuộc đăng nhập Drive, quyền Android và kết nối nguồn trực tuyến."
            NgocSiAiLocalCommand.OPEN_HOME -> return openAppDestination("home", "Đang mở trang chủ.")
            NgocSiAiLocalCommand.SEARCH_ONLINE_MUSIC -> {
                val query = extractNgocSiAiOnlineQuery(originalText)
                    ?: return "Hãy nói nội dung cần tìm, ví dụ: “Tìm nhạc Trịnh Công Sơn”."
                return openAppDestination(
                    "search_online",
                    "Đang tìm “$query” trên các nguồn nhạc trực tuyến.",
                    onlineSearchQuery = query
                )
            }
            NgocSiAiLocalCommand.SEARCH_AND_PLAY_YOUTUBE -> {
                val query = extractNgocSiAiContextualYoutubeAutoPlayQuery(originalText, entryContext)
                    ?: return "Hãy nói rõ nội dung cần tìm rồi phát. Khi mở AI từ trang YouTube, nguồn YouTube sẽ được tự hiểu."
                return openAppDestination(
                    "youtube_search_play",
                    "Đang tìm “$query” trên YouTube và sẽ tự phát video phù hợp đầu tiên khi có kết quả.",
                    query
                )
            }
            NgocSiAiLocalCommand.SEARCH_YOUTUBE -> {
                val query = extractNgocSiAiContextualYoutubeQuery(originalText, entryContext)
                    ?: return "Hãy nói tên nội dung cần tìm, ví dụ: “Tìm YouTube nhạc Trịnh Công Sơn”."
                return openAppDestination("youtube", "Đang tìm trên YouTube: $query", query)
            }
            NgocSiAiLocalCommand.PLAY_RADIO_STATION -> {
                val query = extractNgocSiAiRadioStationQuery(originalText)
                    ?: return "Hãy nói rõ tên đài, ví dụ: “Phát Radio VOV3” hoặc “Mở Radio VOH FM 95.6”."
                return openAppDestination(
                    "radio_station",
                    "Đang mở đài $query.",
                    radioStationQuery = query
                )
            }
            NgocSiAiLocalCommand.OPEN_TV_CHANNEL -> {
                val query = extractNgocSiAiTvChannelQuery(originalText)
                    ?: return "Hãy nói tên kênh, ví dụ: “Mở VTV3” hoặc “Mở kênh HTV7”."
                return openAppDestination(
                    "tv_channel",
                    "Đang mở kênh $query.",
                    tvChannelQuery = query
                )
            }
            NgocSiAiLocalCommand.CREATE_PLAYLIST -> {
                val query = extractNgocSiAiPlaylistQuery(originalText, create = true)
                    ?: return "Hãy nói tên playlist, ví dụ: “Tạo playlist Nhạc Trịnh”."
                return openAppDestination(
                    "create_playlist",
                    "Đang tạo playlist $query.",
                    playlistQuery = query
                )
            }
            NgocSiAiLocalCommand.PLAY_NAMED_PLAYLIST -> {
                val query = extractNgocSiAiPlaylistQuery(originalText, create = false)
                    ?: return "Hãy nói tên playlist muốn phát, ví dụ: “Phát playlist Nhạc Trịnh”."
                return openAppDestination(
                    "play_playlist",
                    "Đang tìm playlist $query.",
                    playlistQuery = query
                )
            }
            NgocSiAiLocalCommand.ADD_CURRENT_TRACK_TO_PLAYLIST -> {
                val query = extractNgocSiAiAddToPlaylistQuery(originalText)
                    ?: return "Hãy nói rõ playlist đích, ví dụ: “Thêm bài đang phát vào playlist Nhạc Trịnh”."
                return openAppDestination(
                    "add_current_to_playlist",
                    "Đang thêm bài đang phát vào playlist $query.",
                    playlistQuery = query
                )
            }
            NgocSiAiLocalCommand.ADD_QUEUE_TO_PLAYLIST -> {
                val query = extractNgocSiAiQueueToPlaylistQuery(originalText)
                    ?: return "Hãy nói playlist đích, ví dụ: “Thêm hàng đợi vào playlist Nhạc Trịnh”."
                return openAppDestination(
                    "add_queue_to_playlist",
                    "Đang lưu hàng đợi vào playlist $query.",
                    playlistQuery = query
                )
            }
            NgocSiAiLocalCommand.SEARCH_DRIVE -> {
                val query = extractNgocSiAiContextualDriveQuery(originalText, entryContext)
                    ?: return "Hãy nói nội dung cần tìm, ví dụ: “Tìm bài hát Diễm xưa trong Drive”."
                return openAppDestination(
                    "drive_search",
                    "Đang mở Drive và tìm “$query” trong danh sách hiện tại.",
                    driveSearchQuery = query
                )
            }
            NgocSiAiLocalCommand.ADD_TRACK_TO_QUEUE -> {
                val query = extractNgocSiAiQueueTrackQuery(originalText)
                    ?: return "Hãy nói rõ tên bài muốn thêm, ví dụ: “Thêm bài hát Diễm xưa vào hàng đợi”."
                return openAppDestination(
                    "queue_track",
                    "Đang tìm “$query” trong thư viện để thêm vào hàng đợi.",
                    trackQuery = query
                )
            }
            NgocSiAiLocalCommand.OPEN_LIBRARY -> return openAppDestination("library", "Đang mở Thư viện nhạc.")
            NgocSiAiLocalCommand.OPEN_FAVORITES -> return openAppDestination("favorites", "Đang mở nhạc Yêu thích.")
            NgocSiAiLocalCommand.OPEN_ALBUMS -> return openAppDestination("albums", "Đang mở danh sách Album.")
            NgocSiAiLocalCommand.OPEN_FOLDERS -> return openAppDestination("folders", "Đang mở Thư mục nhạc.")
            NgocSiAiLocalCommand.OPEN_ONLINE -> return openAppDestination("online", "Đang mở các nguồn Online.")
            NgocSiAiLocalCommand.OPEN_YOUTUBE -> return openAppDestination("youtube", "Đang mở YouTube.")
            NgocSiAiLocalCommand.OPEN_DRIVE -> return openAppDestination("drive", "Đang mở Google Drive.")
            NgocSiAiLocalCommand.OPEN_RADIO -> return openAppDestination("radio", "Đang mở Radio.")
            NgocSiAiLocalCommand.OPEN_TV -> return openAppDestination("tv", "Đang mở Truyền hình.")
            NgocSiAiLocalCommand.OPEN_PLAYLISTS -> return openAppDestination("playlists", "Đang mở danh sách phát.")
            NgocSiAiLocalCommand.OPEN_QUEUE -> return openAppDestination("queue", "Đang mở hàng đợi phát.")
            NgocSiAiLocalCommand.OPEN_PLAYER -> return openAppDestination("player", "Đang mở trình phát.")
            NgocSiAiLocalCommand.OPEN_SETTINGS -> return openAppDestination("settings", "Đang mở Cài đặt.")
            NgocSiAiLocalCommand.FAVORITE_CURRENT_TRACK -> return openAppDestination(
                "favorite_current",
                "Đang thêm bài hiện tại vào Yêu thích."
            )
            NgocSiAiLocalCommand.OPEN_SLEEP_TIMER -> return openAppDestination("sleep_timer", "Đang mở hẹn giờ tắt nhạc.")
            NgocSiAiLocalCommand.SLEEP_TIMER_15 -> return openAppDestination("sleep_timer_15", "Đang đặt hẹn giờ tắt nhạc sau 15 phút.")
            NgocSiAiLocalCommand.SLEEP_TIMER_30 -> return openAppDestination("sleep_timer_30", "Đang đặt hẹn giờ tắt nhạc sau 30 phút.")
            NgocSiAiLocalCommand.SLEEP_TIMER_45 -> return openAppDestination("sleep_timer_45", "Đang đặt hẹn giờ tắt nhạc sau 45 phút.")
            NgocSiAiLocalCommand.SLEEP_TIMER_60 -> return openAppDestination("sleep_timer_60", "Đang đặt hẹn giờ tắt nhạc sau 60 phút.")
            NgocSiAiLocalCommand.SLEEP_TIMER_90 -> return openAppDestination("sleep_timer_90", "Đang đặt hẹn giờ tắt nhạc sau 90 phút.")
            NgocSiAiLocalCommand.SLEEP_TIMER_120 -> return openAppDestination("sleep_timer_120", "Đang đặt hẹn giờ tắt nhạc sau 120 phút.")
            NgocSiAiLocalCommand.SLEEP_TIMER_CUSTOM -> {
                val minutes = extractNgocSiAiSleepTimerMinutes(originalText)
                    ?: return "Hãy nói thời lượng từ 1 phút đến 24 giờ, ví dụ: “AI, hẹn giờ 5 phút”."
                return openAppDestination(
                    "sleep_timer_custom",
                    "Đang đặt hẹn giờ tắt nhạc sau $minutes phút.",
                    sleepTimerMinutes = minutes
                )
            }
            NgocSiAiLocalCommand.CANCEL_SLEEP_TIMER -> return openAppDestination("sleep_timer_off", "Đã yêu cầu hủy hẹn giờ.")
            else -> Unit
        }

        if (command == NgocSiAiLocalCommand.STOP) return stopActiveMusicFromAi()
        if (prefs.getBoolean("youtube_active_source", false) &&
            command in setOf(
                NgocSiAiLocalCommand.SET_PLAYBACK_SPEED,
                NgocSiAiLocalCommand.SEEK_FORWARD_10,
                NgocSiAiLocalCommand.SEEK_BACK_10
            )
        ) {
            return "Tua và đổi tốc độ từ AI hiện áp dụng cho nhạc điện thoại, Drive và Radio. Hãy dùng điều khiển riêng trong trình phát YouTube."
        }

        val availablePlayer = preferredPlaybackController()
        if (command == NgocSiAiLocalCommand.PLAY_TRACK) {
            val query = extractNgocSiAiTrackQuery(originalText)
                ?: return "Hãy nói tên bài, ví dụ: “Phát bài hát Diễm xưa”."
            val matchIndex = availablePlayer?.let { currentPlayer ->
                findNgocSiAiTrackMatchIndex(currentPlayer, query)
            }
            if (matchIndex != null && availablePlayer != null) {
                availablePlayer.seekTo(matchIndex, 0L)
                availablePlayer.play()
                val title = availablePlayer.getMediaItemAt(matchIndex).mediaMetadata.title?.toString()
                    ?.takeIf { it.isNotBlank() } ?: query
                return "Đang phát $title."
            }
            return openAppDestination(
                "play_track",
                "Đang tìm bài “$query” trong toàn bộ thư viện.",
                trackQuery = query
            )
        }
        val player = availablePlayer
            ?: return "Trình phát đang khởi động hoặc chưa kết nối. Hãy thử lại sau một lát."
        if (command != NgocSiAiLocalCommand.STOP &&
            (player.mediaItemCount == 0 || player.currentMediaItem == null)
        ) {
            val audioPermission = if (Build.VERSION.SDK_INT >= 33) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            if (ContextCompat.checkSelfPermission(this, audioPermission) != PackageManager.PERMISSION_GRANTED) {
                return "Chưa có quyền đọc nhạc. Hãy cấp quyền Âm nhạc và âm thanh cho NGỌC SĨ AI Preview trong Cài đặt ứng dụng."
            }
            // Preserve an explicit play request while the device library is loading.
            val requestedTrack = if (command == NgocSiAiLocalCommand.PLAY_TRACK) {
                extractNgocSiAiTrackQuery(originalText)
            } else null
            pendingTrackQueryAfterQueueLoad = requestedTrack
            pendingPlayAfterQueueLoad = command == NgocSiAiLocalCommand.PLAY ||
                command == NgocSiAiLocalCommand.PLAY_SECOND || requestedTrack != null
            pendingQueueStartIndexAfterLoad = if (command == NgocSiAiLocalCommand.PLAY_SECOND) 1 else if (command == NgocSiAiLocalCommand.PLAY) 0 else null
            if (!deviceQueueLoadStarted) {
                loadDeviceMusicIntoQueue()
                return when {
                    requestedTrack != null -> "Đang nạp thư viện; tôi sẽ tìm và phát bài $requestedTrack ngay khi sẵn sàng."
                    command == NgocSiAiLocalCommand.PLAY -> "Đang nạp thư viện nhạc; tôi sẽ tự phát ngay khi nạp xong."
                    else -> "Đang kiểm tra thư viện nhạc trên điện thoại. Hãy thử lại sau khi nạp xong."
                }
            }
            return if (requestedTrack != null) {
                "Thư viện đang nạp; tôi sẽ tìm bài $requestedTrack khi sẵn sàng."
            } else if (command == NgocSiAiLocalCommand.PLAY) {
                "Thư viện đang nạp; tôi sẽ tự phát nhạc ngay khi sẵn sàng."
            } else {
                "Thư viện nhạc đang được nạp. Hãy đợi một chút rồi thử lại."
            }
        }

        return executeNgocSiAiMediaCommand(player, command, originalText)
            ?: "Yêu cầu này cần mở mục chức năng tương ứng trong ứng dụng."
    }

    private fun openAppDestination(
        destination: String,
        response: String,
        youtubeSearchQuery: String? = null,
        radioStationQuery: String? = null,
        tvChannelQuery: String? = null,
        playlistQuery: String? = null,
        youtubeAction: String? = null,
        onlineSearchQuery: String? = null,
        trackQuery: String? = null,
        driveSearchQuery: String? = null,
        sleepTimerMinutes: Int? = null
    ): String {
        return runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    putExtra("pro_destination", destination)
                    youtubeSearchQuery?.let { putExtra("ai_youtube_query", it) }
                    radioStationQuery?.let { putExtra("ai_radio_station", it) }
                    tvChannelQuery?.let { putExtra("ai_tv_channel", it) }
                    playlistQuery?.let { putExtra("ai_playlist_query", it) }
                    youtubeAction?.let { putExtra("ai_youtube_action", it) }
                    onlineSearchQuery?.let { putExtra("ai_online_query", it) }
                    trackQuery?.let { putExtra("ai_track_query", it) }
                    driveSearchQuery?.let { putExtra("ai_drive_query", it) }
                    sleepTimerMinutes?.let { putExtra("ai_sleep_timer_minutes", it) }
                    // Bring the existing main app to the front and deliver its navigation event.
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
            response
        }.getOrElse {
            "Chưa mở được chức năng trong ứng dụng. Hãy mở chức năng đó từ trang chủ."
        }
    }

    private fun speakVoiceReply(message: String, force: Boolean = false) {
        if (!force && !voiceRepliesEnabled) return
        val cleanMessage = NgocSiAiSpeechTextRules.cleanForSpeech(message)
        if (cleanMessage.isEmpty()) return

        val tts = responseTts
        if (!responseTtsInitialized || tts == null) {
            // TextToSpeech initializes asynchronously. Queue the response instead of losing it.
            pendingVoiceReply = cleanMessage
            pendingVoiceReplyForce = force
            return
        }
        if (!responseTtsReady) {
            Toast.makeText(
                this,
                "Chưa có giọng đọc khả dụng. Hãy vào cài đặt Văn bản thành giọng nói và tải dữ liệu tiếng Việt.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val result = runCatching {
            tts.speak(
                cleanMessage,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "ngoc_si_activity_reply_${System.currentTimeMillis()}"
            )
        }.getOrDefault(TextToSpeech.ERROR)
        if (result == TextToSpeech.ERROR) {
            Toast.makeText(this, "Không phát được giọng nói. Hãy kiểm tra cài đặt Văn bản thành giọng nói.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        wakeWordStatusHandler.removeCallbacksAndMessages(null)
        closeOwnerVoiceAudioSource()
        musicController?.release()
        musicController = null
        productionMusicController?.release()
        productionMusicController = null
        runCatching { responseTts?.stop() }
        runCatching { voiceRecognizer?.cancel() }
        runCatching { voiceRecognizer?.destroy() }
        voiceRecognizer = null
        voiceRecognitionInProgress = false
        voiceRetryHandler.removeCallbacksAndMessages(null)
        speechFallbackHandler.removeCallbacksAndMessages(null)
        activeVoiceIntent = null
        runCatching { responseTts?.shutdown() }
        responseTts = null
        responseTtsInitialized = false
        responseTtsReady = false
        pendingVoiceReply = null
        partialSpeechTranscript = ""
        super.onDestroy()
    }

    private fun toggleVoiceReplies() {
        voiceRepliesEnabled = !voiceRepliesEnabled
        getSharedPreferences(AI_CHAT_PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(AI_VOICE_REPLY_KEY, voiceRepliesEnabled)
            .apply()
        if (voiceRepliesEnabled) {
            speakVoiceReply("Đã bật đọc câu trả lời bằng giọng nói.")
        } else {
            runCatching { responseTts?.stop() }
            Toast.makeText(this, "Đã tắt đọc câu trả lời bằng giọng nói.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshOwnerVoiceState() {
        ownerVoiceEnrolled = NgocSiVoiceProfile.hasProfile(this)
        ownerVoiceLockEnabled = NgocSiVoiceProfile.isLockEnabled(this)
    }

    private fun requestOwnerVoiceEnrollment() {
        NgocSiVoiceProfile.setLockEnabled(this, false)
        ownerVoiceLockEnabled = false
        Toast.makeText(this, "Đã bỏ đăng ký và xác minh giọng nói. Bạn có thể nói lệnh trực tiếp với AI.", Toast.LENGTH_LONG).show()
    }

    private fun toggleOwnerVoiceLock() {
        NgocSiVoiceProfile.setLockEnabled(this, false)
        ownerVoiceLockEnabled = false
        Toast.makeText(this, "Bảo vệ giọng nói đã được gỡ bỏ. AI sẽ nhận lệnh mà không xác minh người nói.", Toast.LENGTH_LONG).show()
    }

    private fun toggleWakeWordListening(entryContext: String? = null) {
        if (wakeWordListening) {
            NgocSiWakeWordService.stop(this)
            wakeWordListening = false
            wakeWordStatusHandler.removeCallbacks(wakeWordStatusChecker)
            return
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                NgocSiWakeWordService.start(
                        this,
                        entryContext,
                        youtubeVideoId = entryYoutubeVideoId,
                        mediaTitle = intent.getStringExtra(NGOC_SI_AI_ENTRY_MEDIA_TITLE_EXTRA),
                        mediaChannel = intent.getStringExtra(NGOC_SI_AI_ENTRY_MEDIA_CHANNEL_EXTRA)
                    )
                wakeWordListening = true
                monitorWakeWordStatus()
            } catch (_: Exception) {
                Toast.makeText(this, "Không thể bật nghe từ khóa. Hãy thử lại.", Toast.LENGTH_LONG).show()
            }
        } else {
            wakeWordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun verifyOwnerVoice(capturedPcm: ByteArray): Boolean {
        if (!NgocSiVoiceProfile.isLockEnabled(this)) return true
        val enrolled = NgocSiVoiceProfile.loadProfiles(this) ?: return false
        val candidate = NgocSiVoiceProfile.extractFeatures(this@NgocSiAiActivity, capturedPcm) ?: return false
        return NgocSiVoiceProfile.matches(enrolled, candidate)
    }

    private fun deliverRecognizedSpeechAfterVoiceCheck(phrase: String, capturedPcm: ByteArray) {
        if (phrase.isBlank() || isFinishing || isDestroyed) return
        if (!NgocSiVoiceProfile.isLockEnabled(this)) {
            recognizedSpeech = phrase
            recognizedSpeechShouldSend = true
            return
        }
        if (capturedPcm.isEmpty()) {
            Toast.makeText(
                this,
                "Không có dữ liệu âm thanh để xác minh giọng. Hãy nhấn micro và nói lại.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        ownerVoiceVerificationInProgress = true
        lifecycleScope.launch {
            val accepted = withContext(Dispatchers.Default) {
                runCatching { verifyOwnerVoice(capturedPcm) }.getOrDefault(false)
            }
            ownerVoiceVerificationInProgress = false
            if (isFinishing || isDestroyed) return@launch
            if (!accepted) {
                Toast.makeText(
                    this@NgocSiAiActivity,
                    "Không xác minh được giọng đã đăng ký. Câu nói chưa được gửi hoặc thực hiện.",
                    Toast.LENGTH_LONG
                ).show()
                speakVoiceReply(
                    "Tôi nghe được câu lệnh nhưng mẫu giọng lưu chưa khớp đủ rõ. Hãy đăng ký lại ba mẫu giọng trong phần bảo vệ giọng nói, rồi thử lại.",
                    force = true
                )
                return@launch
            }
            recognizedSpeech = phrase
            recognizedSpeechShouldSend = true
        }
    }

    private fun startOwnerVoiceCaptureAndTranscribe() {
        lifecycleScope.launch {
            val pcm = withContext(Dispatchers.IO) {
                NgocSiOwnerVoiceAudio.captureUtterance(
                    shouldStop = { isFinishing || isDestroyed },
                    onVoiceDetected = {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                partialSpeechTranscript = "Đang thu giọng để xác minh…"
                            }
                        }
                    }
                )
            }
            if (isFinishing || isDestroyed) {
                voiceRecognitionInProgress = false
                partialSpeechTranscript = ""
                return@launch
            }
            if (pcm == null) {
                voiceRecognitionInProgress = false
                partialSpeechTranscript = ""
                Toast.makeText(
                    this@NgocSiAiActivity,
                    "Chưa thu được câu nói rõ ràng. Hãy nói gần điện thoại hơn rồi thử lại.",
                    Toast.LENGTH_LONG
                ).show()
                speakVoiceReply("Tôi chưa nghe rõ câu lệnh. Hãy nói gần điện thoại hơn rồi thử lại.", force = true)
                return@launch
            }

            partialSpeechTranscript = "Đang nhận dạng tiếng Việt ngoại tuyến…"
            val phrase = withContext(Dispatchers.Default) {
                NgocSiOwnerVoiceTranscriber.transcribe(applicationContext, pcm)
            }
            voiceRecognitionInProgress = false
            partialSpeechTranscript = ""
            if (isFinishing || isDestroyed) return@launch
            if (phrase.isNullOrBlank()) {
                Toast.makeText(
                    this@NgocSiAiActivity,
                    "Đã thu âm nhưng chưa nhận dạng được câu lệnh. Hãy nói chậm, đủ câu và thử lại.",
                    Toast.LENGTH_LONG
                ).show()
                speakVoiceReply(
                    "Tôi đã nghe âm thanh nhưng chưa nhận dạng được câu lệnh. Hãy nói chậm hơn rồi thử lại.",
                    force = true
                )
                return@launch
            }
            Toast.makeText(
                this@NgocSiAiActivity,
                "Đã nhận giọng nói: ${phrase.take(100)}",
                Toast.LENGTH_SHORT
            ).show()
            deliverRecognizedSpeechAfterVoiceCheck(phrase, pcm)
        }
    }

    private fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            voiceInputPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (voiceRecognitionInProgress || ownerVoiceVerificationInProgress) {
            Toast.makeText(
                this,
                if (ownerVoiceVerificationInProgress) "Đang xác minh giọng nói trên điện thoại."
                else "Micro đang nghe. Hãy nói lệnh hoặc đợi kết thúc.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        speechFallbackUsed = false
        speechHasEnded = false
        speechFallbackHandler.removeCallbacks(speechNoTranscriptWatchdog)
        speechFallbackHandler.removeCallbacks(speechPartialFinalizationWatchdog)
        voiceInputAudioBuffer.reset()
        if (NgocSiVoiceProfile.isLockEnabled(this)) {
            // Protected mode transcribes the just-recorded utterance locally; no injected
            // audio or Android speech-service fallback is involved.
            voiceRecognitionInProgress = true
            partialSpeechTranscript = "Đang chờ giọng nói…"
            Toast.makeText(
                this,
                "Nhận dạng tiếng Việt ngoại tuyến trên điện thoại.",
                Toast.LENGTH_SHORT
            ).show()
            startOwnerVoiceCaptureAndTranscribe()
            return
        }

        val lockEnabled = NgocSiVoiceProfile.isLockEnabled(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putExtra(
                    RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                    RecognizerIntent.FORMATTING_OPTIMIZE_LATENCY
                )
            }
            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                "Nói tiếng Việt, ví dụ: Phát nhạc, mở YouTube, mở Drive, mở Yêu thích hoặc hẹn giờ"
            )
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            if (lockEnabled) {
                Toast.makeText(
                    this,
                    "Dịch vụ nhận dạng trên máy không hỗ trợ xác minh âm thanh. Hãy cập nhật dịch vụ Google rồi thử lại.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                voiceRecognitionInProgress = true
                partialSpeechTranscript = ""
                runCatching { speechLauncher.launch(intent) }
                    .onFailure {
                        voiceRecognitionInProgress = false
                        Toast.makeText(
                            this,
                            "Không mở được dịch vụ nhận dạng giọng nói. Hãy cập nhật ứng dụng Google hoặc Speech Services.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
            return
        }

        try {
            speechFallbackUsed = false
            speechFallbackHandler.removeCallbacks(speechNoTranscriptWatchdog)
            voiceInputAudioBuffer.reset()
            closeOwnerVoiceAudioSource()
            val recognizer = voiceRecognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(inAppSpeechListener)
                voiceRecognizer = it
            }
            voiceRecognitionInProgress = true
            partialSpeechTranscript = ""
            voiceRetryCount = 0
            activeVoiceIntent = intent
            Toast.makeText(
                this,
                if (lockEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    "Đang nghe và xác minh giọng nói trên điện thoại…"
                else "Đang nghe… hãy nói rõ câu lệnh, không cần nói quá nhanh.",
                Toast.LENGTH_SHORT
            ).show()

            if (lockEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                startOwnerVoiceCaptureAndRecognition(recognizer, intent)
            } else {
                recognizer.startListening(intent)
                speechFallbackHandler.postDelayed(speechNoTranscriptWatchdog, 10_000L)
            }
        } catch (_: SecurityException) {
            closeOwnerVoiceAudioSource()
            voiceRecognitionInProgress = false
            partialSpeechTranscript = ""
            Toast.makeText(this, "Thiếu quyền micro. Hãy cấp quyền micro cho NGỌC SĨ AI Preview.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            closeOwnerVoiceAudioSource()
            voiceRecognitionInProgress = false
            partialSpeechTranscript = ""
            runCatching {
                runCatching { voiceRecognizer?.destroy() }
                voiceRecognizer = null
                if (lockEnabled) {
                    Toast.makeText(
                        this,
                        "Không thể xác minh giọng trong chế độ nhận dạng hiện tại. Hãy thử lại micro.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    speechLauncher.launch(intent)
                }
            }.onFailure {
                Toast.makeText(
                    this,
                    "Không mở được dịch vụ nhận dạng giọng nói. Hãy cập nhật ứng dụng Google hoặc Speech Services by Google.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startOwnerVoiceCaptureAndRecognition(
        recognizer: SpeechRecognizer,
        intent: Intent
    ) {
        lifecycleScope.launch {
            val pcm = withContext(Dispatchers.IO) {
                NgocSiOwnerVoiceAudio.captureUtterance(
                    shouldStop = { isFinishing || isDestroyed },
                    onVoiceDetected = {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                partialSpeechTranscript = "Đang thu giọng để xác minh…"
                            }
                        }
                    }
                )
            }
            if (isFinishing || isDestroyed) {
                closeOwnerVoiceAudioSource()
                return@launch
            }
            if (pcm == null) {
                voiceRecognitionInProgress = false
                partialSpeechTranscript = ""
                Toast.makeText(
                    this@NgocSiAiActivity,
                    "Chưa thu được câu nói rõ ràng. Hãy nhấn micro và nói gần điện thoại hơn.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val pipe = NgocSiVoiceAudioSourcePipe.create(intent, pcm)
            if (pipe == null) {
                voiceRecognitionInProgress = false
                partialSpeechTranscript = ""
                Toast.makeText(
                    this@NgocSiAiActivity,
                    "Không thể chuyển âm thanh sang nhận dạng để xác minh giọng. Hãy thử lại.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            pendingOwnerVoicePcm = pcm
            ownerVoiceAudioSourcePipe = pipe
            try {
                recognizer.startListening(intent)
                pipe.writeAsync(lifecycleScope)
                speechFallbackHandler.removeCallbacks(speechNoTranscriptWatchdog)
                speechFallbackHandler.postDelayed(speechNoTranscriptWatchdog, 10_000L)
            } catch (_: Exception) {
                closeOwnerVoiceAudioSource()
                voiceRecognitionInProgress = false
                partialSpeechTranscript = ""
                Toast.makeText(
                    this@NgocSiAiActivity,
                    "Dịch vụ nhận dạng chưa xử lý được âm thanh để xác minh giọng. Hãy thử lại.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun closeOwnerVoiceAudioSource() {
        pendingOwnerVoicePcm = null
        ownerVoiceAudioSourcePipe?.close()
        ownerVoiceAudioSourcePipe = null
    }
}
@UnstableApi
@Composable
private fun NgocSiAiPreviewScreen(
    isOnlineConfigured: Boolean,
    entryContext: String?,
    chatPreferences: SharedPreferences,
    onIsLocalModelInstalled: () -> Boolean,
    onDownloadLocalModel: suspend (onProgress: (Long, Long) -> Unit) -> Unit,
    onGenerateLocalModel: suspend (String, List<NgocSiAiMessage>) -> String,
    onReleaseLocalModel: suspend () -> Unit,
    onLocalModelBackendStatus: () -> String,
    onSendOnline: NgocSiAiRequest,
    onExecuteAppCommand: NgocSiAiToolExecutor,
    onLocalCommand: (NgocSiAiLocalCommand, String) -> String,
    onVoiceReply: (String) -> Unit,
    onSpeakMessage: (String) -> Unit,
    voiceRepliesEnabled: Boolean,
    onToggleVoiceReplies: () -> Unit,
    voiceOptions: List<NgocSiAiVoiceOption>,
    selectedVoiceName: String,
    onVoiceSelected: (String) -> Unit,
    onManageVoices: () -> Unit,
    recognizedSpeech: String,
    recognizedSpeechShouldSend: Boolean,
    partialSpeechTranscript: String,
    isVoiceListening: Boolean,
    onSpeechConsumed: () -> Unit,
    onBack: () -> Unit,
    onVoice: () -> Unit,
    isWakeWordListening: Boolean,
    onToggleWakeWord: () -> Unit,
    wakeWordConversationTurnId: Long,
    wakeWordConversationRevision: Long,
    wakeWordConversationQuestion: String,
    wakeWordConversationAnswer: String,
) {
    val messages = remember {
        mutableStateListOf<AiPreviewMessage>().apply {
            addAll(loadAiPreviewMessages(chatPreferences, isOnlineConfigured))
        }
    }
    var input by remember { mutableStateOf("") }
    val conversation = remember {
        mutableStateListOf<NgocSiAiMessage>().apply {
            addAll(loadAiApiConversation(chatPreferences))
        }
    }
    val pendingWakeWordContextTurns = remember {
        mutableStateListOf<PendingWakeWordContextTurn>()
    }
    val coroutineScope = rememberCoroutineScope()
    var isSending by remember { mutableStateOf(false) }
    var isSendingLocalModel by remember { mutableStateOf(false) }
    var quotaNote by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showVoicePicker by remember { mutableStateOf(false) }
    var localModelInstalled by remember { mutableStateOf(onIsLocalModelInstalled()) }
    var localModelDownloading by remember { mutableStateOf(false) }
    var localModelDownloadedBytes by remember { mutableStateOf(0L) }
    var localModelDownloadTotalBytes by remember { mutableStateOf(NgocSiAiOnDeviceModel.MODEL_SIZE_BYTES) }
    var localModelDownloadError by remember { mutableStateOf("") }
    var preferLocalModel by remember {
        mutableStateOf(chatPreferences.getBoolean(NGOC_SI_AI_PREFER_LOCAL_MODEL_KEY, false))
    }
    val messageListState = androidx.compose.foundation.lazy.rememberLazyListState()

    fun persistChatState() {
        saveAiPreviewMessages(chatPreferences, messages)
        saveAiApiConversation(chatPreferences, conversation)
    }

    fun appendWakeWordContextTurn(turn: PendingWakeWordContextTurn) {
        val storedTurnId = chatPreferences.getLong(AI_WAKEWORD_LAST_CONTEXT_TURN_KEY, 0L)
        if (turn.turnId <= storedTurnId) return
        if (isSending) {
            // Do not insert a voice turn after an unfinished screen-chat user message.
            // It is merged only after that request has completed or rolled back.
            pendingWakeWordContextTurns.removeAll { it.turnId == turn.turnId }
            pendingWakeWordContextTurns.add(turn)
            return
        }
        val updated = appendCompletedNgocSiAiTurn(
            conversation.toList(), turn.question, turn.answer
        )
        conversation.clear()
        conversation.addAll(updated)
        chatPreferences.edit().putLong(AI_WAKEWORD_LAST_CONTEXT_TURN_KEY, turn.turnId).apply()
        saveAiApiConversation(chatPreferences, conversation)
    }

    fun flushPendingWakeWordContextTurns() {
        if (pendingWakeWordContextTurns.isEmpty()) return
        var latestStoredTurnId = chatPreferences.getLong(AI_WAKEWORD_LAST_CONTEXT_TURN_KEY, 0L)
        pendingWakeWordContextTurns.sortedBy { it.turnId }.forEach { turn ->
            if (turn.turnId > latestStoredTurnId) {
                val updated = appendCompletedNgocSiAiTurn(
                    conversation.toList(), turn.question, turn.answer
                )
                conversation.clear()
                conversation.addAll(updated)
                latestStoredTurnId = turn.turnId
            }
        }
        pendingWakeWordContextTurns.clear()
        chatPreferences.edit()
            .putLong(AI_WAKEWORD_LAST_CONTEXT_TURN_KEY, latestStoredTurnId)
            .apply()
        saveAiApiConversation(chatPreferences, conversation)
    }

    LaunchedEffect(wakeWordConversationRevision) {
        val turnId = wakeWordConversationTurnId
        val question = wakeWordConversationQuestion.trim()
        val answer = wakeWordConversationAnswer.trim()
        if (turnId <= 0L || question.isBlank() || answer.isBlank()) return@LaunchedEffect

        val displayedTurnId = chatPreferences.getLong(AI_WAKEWORD_LAST_DISPLAYED_TURN_KEY, 0L)
        val contextTurnId = chatPreferences.getLong(AI_WAKEWORD_LAST_CONTEXT_TURN_KEY, 0L)
        if (turnId > displayedTurnId) {
            messages.add(AiPreviewMessage(true, question))
            messages.add(AiPreviewMessage(false, answer))
            while (messages.size > MAX_SAVED_AI_UI_MESSAGES) messages.removeAt(0)
            chatPreferences.edit().putLong(AI_WAKEWORD_LAST_DISPLAYED_TURN_KEY, turnId).apply()
        } else if (
            turnId == displayedTurnId &&
            answer != NgocSiWakeWordService.CONVERSATION_PENDING_REPLY
        ) {
            val lastUserIndex = messages.indexOfLast { it.isUser }
            val pendingReplyIndex = lastUserIndex + 1
            val hasMatchingPendingReply = lastUserIndex >= 0 &&
                messages[lastUserIndex].text == question &&
                pendingReplyIndex == messages.lastIndex &&
                pendingReplyIndex >= 0 &&
                !messages[pendingReplyIndex].isUser &&
                messages[pendingReplyIndex].text == NgocSiWakeWordService.CONVERSATION_PENDING_REPLY
            if (hasMatchingPendingReply) {
                messages[pendingReplyIndex] = AiPreviewMessage(false, answer)
            } else if (messages.lastOrNull()?.let { !it.isUser && it.text == answer } != true) {
                messages.add(AiPreviewMessage(false, answer))
                while (messages.size > MAX_SAVED_AI_UI_MESSAGES) messages.removeAt(0)
            }
        }

        if (
            answer != NgocSiWakeWordService.CONVERSATION_PENDING_REPLY &&
            turnId > contextTurnId
        ) {
            appendWakeWordContextTurn(
                PendingWakeWordContextTurn(turnId, question, answer)
            )
        }
        // Persist the visible transcript immediately, but never overwrite completed API
        // history with an unfinished screen-chat turn while a Gemini request is in flight.
        saveAiPreviewMessages(chatPreferences, messages)
        if (!isSending) saveAiApiConversation(chatPreferences, conversation)
    }

    LaunchedEffect(messages.size, isSending) {
        val finalVisibleIndex = messages.lastIndex + if (isSending) 1 else 0
        if (finalVisibleIndex >= 0) {
            messageListState.animateScrollToItem(finalVisibleIndex)
        }
    }

    fun sendMessage(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        // Voice-control exit phrases are handled before music-command classification
        // or Gemini so "AI, tạm dừng giọng nói" never becomes a music-pause request.
        if (isNgocSiAiStopVoiceControlPhrase(clean)) {
            val wasListening = isWakeWordListening
            messages.add(AiPreviewMessage(true, clean))
            if (messages.size > MAX_SAVED_AI_UI_MESSAGES) messages.removeAt(0)
            input = ""
            if (wasListening) onToggleWakeWord()
            val voiceControlReply = if (wasListening) {
                "Đã tắt điều khiển giọng nói. Nhạc vẫn tiếp tục phát."
            } else {
                "Điều khiển giọng nói hiện đã tắt; nhạc không bị ảnh hưởng."
            }
            messages.add(AiPreviewMessage(false, voiceControlReply))
            if (messages.size > MAX_SAVED_AI_UI_MESSAGES) messages.removeAt(0)
            if (!isSending) {
                conversation.add(NgocSiAiMessage(role = "user", content = clean))
                conversation.add(NgocSiAiMessage(role = "assistant", content = voiceControlReply))
                while (conversation.size > NGOC_SI_AI_MAX_HISTORY_MESSAGES) {
                    conversation.removeAt(0)
                    if (conversation.firstOrNull()?.role == "assistant") conversation.removeAt(0)
                }
            }
            persistChatState()
            onVoiceReply(voiceControlReply)
            return
        }

        // All allowlisted on-device commands and short local replies stay responsive
        // while an unrelated Gemini request is still in flight.
        val contextualActionConfirmation =
            isNgocSiAiContextualActionConfirmation(clean, conversation)
        val explicitStop = isNgocSiAiExplicitStopPhrase(clean)
        // A direct playback command remains local and immediate. A short confirmation of
        // the previous assistant's explicit app-action offer must reach Gemini instead of
        // being consumed by the generic "được/ok" small-talk fallback or resume classifier.
        // Explicit memory commands take priority over the app command router so
        // they are always handled locally and never become playback/navigation actions.
        val localMemoryReply = handleNgocSiAiMemoryCommand(clean, chatPreferences)
        val localMemories = decodeNgocSiAiMemory(
            chatPreferences.getString(NGOC_SI_AI_LONG_TERM_MEMORY_KEY, null)
        )
        val localMemoryQuestionReply =
            answerNgocSiAiSavedPreferenceQuestion(clean, localMemories)
        val localCommand = if (
            localMemoryReply != null || localMemoryQuestionReply != null || contextualActionConfirmation
        ) {
            null
        } else if (explicitStop) {
            NgocSiAiLocalCommand.STOP
        } else {
            classifyNgocSiAiLocalCommand(clean, entryContext)
        }
        val localReply = localMemoryReply
            ?: localMemoryQuestionReply
            ?: if (localCommand == null && !contextualActionConfirmation) {
                localConversationFallback(
                    userText = clean,
                    previousAssistantReply = messages.lastOrNull { !it.isUser }?.text,
                    // When a real model is available, let it continue the conversation from history.
                    // Keep the short offline explanation only when neither remote nor on-device AI exists.
                    allowContinuationFallback = !isOnlineConfigured && !localModelInstalled
                )
            } else null
        if (isSending && localCommand == null && localReply == null) return

        messages.add(AiPreviewMessage(true, clean))
        if (messages.size > MAX_SAVED_AI_UI_MESSAGES) messages.removeAt(0)
        input = ""
        if (localCommand != null) {
            val commandReply = onLocalCommand(localCommand, clean)
            messages.add(AiPreviewMessage(false, commandReply))
            if (messages.size > MAX_SAVED_AI_UI_MESSAGES) messages.removeAt(0)
            // Preserve local turns in future model context only when no earlier request
            // is in flight; never interleave them inside an unfinished user/assistant pair.
            if (!isSending) {
                conversation.add(NgocSiAiMessage(role = "user", content = clean))
                conversation.add(NgocSiAiMessage(role = "assistant", content = commandReply))
                while (conversation.size > NGOC_SI_AI_MAX_HISTORY_MESSAGES) {
                    conversation.removeAt(0)
                    if (conversation.firstOrNull()?.role == "assistant") conversation.removeAt(0)
                }
                saveAiApiConversation(chatPreferences, conversation)
            }
            if (isSending) {
                // Do not persist an unfinished remote user turn when a local command
                // interrupts the wait; the in-flight result will complete or roll it back.
                saveAiPreviewMessages(chatPreferences, messages)
            } else {
                persistChatState()
            }
            onVoiceReply(commandReply)
            return
        }

        // Keep predictable greetings/help local and preserve them for later context without
        // spending an online request.
        if (localReply != null) {
            messages.add(AiPreviewMessage(false, localReply))
            if (messages.size > MAX_SAVED_AI_UI_MESSAGES) messages.removeAt(0)
            if (!isSending) {
                conversation.add(NgocSiAiMessage(role = "user", content = clean))
                conversation.add(NgocSiAiMessage(role = "assistant", content = localReply))
                while (conversation.size > NGOC_SI_AI_MAX_HISTORY_MESSAGES) {
                    conversation.removeAt(0)
                    if (conversation.firstOrNull()?.role == "assistant") conversation.removeAt(0)
                }
                saveAiApiConversation(chatPreferences, conversation)
            }
            if (isSending) {
                saveAiPreviewMessages(chatPreferences, messages)
            } else {
                persistChatState()
            }
            onVoiceReply(localReply)
            return
        }

        val localModelNeedsAppTools =
            shouldOfferNgocSiAiAppCommandTool(clean, conversation.toList())
        if (preferLocalModel && localModelNeedsAppTools) {
            val safeReply =
                "Chế độ ưu tiên AI tại máy chỉ trò chuyện và không tự gọi công cụ điều khiển nhiều bước. " +
                    "Ứng dụng chưa thực hiện lệnh này. Hãy tắt “Ưu tiên AI tại máy” để Gemini xử lý, " +
                    "hoặc chia yêu cầu thành từng lệnh đơn đã được hỗ trợ."
            messages.add(AiPreviewMessage(false, safeReply))
            if (messages.size > MAX_SAVED_AI_UI_MESSAGES) messages.removeAt(0)
            conversation.add(NgocSiAiMessage(role = "user", content = clean))
            conversation.add(NgocSiAiMessage(role = "assistant", content = safeReply))
            while (conversation.size > NGOC_SI_AI_MAX_HISTORY_MESSAGES) {
                conversation.removeAt(0)
                if (conversation.firstOrNull()?.role == "assistant") conversation.removeAt(0)
            }
            persistChatState()
            onVoiceReply(safeReply)
            return
        }

        if (localModelInstalled &&
            !localModelNeedsAppTools &&
            (preferLocalModel || !isOnlineConfigured)
        ) {
            val userTurn = NgocSiAiMessage(role = "user", content = clean)
            conversation.add(userTurn)
            while (conversation.size > NGOC_SI_AI_MAX_HISTORY_MESSAGES) {
                conversation.removeAt(0)
                if (conversation.firstOrNull()?.role == "assistant") conversation.removeAt(0)
            }
            saveAiPreviewMessages(chatPreferences, messages)
            val priorLocalContext = conversation.dropLast(1).toList()
            isSendingLocalModel = true
            isSending = true
            coroutineScope.launch {
                try {
                    val localAnswer = onGenerateLocalModel(clean, priorLocalContext)
                    conversation.add(NgocSiAiMessage(role = "assistant", content = localAnswer))
                    saveAiApiConversation(chatPreferences, conversation)
                    messages.add(AiPreviewMessage(false, localAnswer))
                    while (messages.size > MAX_SAVED_AI_UI_MESSAGES) messages.removeAt(0)
                    quotaNote = "Trả lời tại máy · ${onLocalModelBackendStatus()} · không gửi câu hỏi lên Gemini"
                    persistChatState()
                    onVoiceReply(localAnswer)
                } catch (error: Exception) {
                    if (conversation.lastOrNull() == userTurn) conversation.removeAt(conversation.lastIndex)
                    val errorReply = "AI ngoại tuyến chưa xử lý được câu hỏi này: " +
                        (error.message ?: "Hãy thử câu hỏi ngắn hơn.")
                    messages.add(AiPreviewMessage(false, errorReply))
                    while (messages.size > MAX_SAVED_AI_UI_MESSAGES) messages.removeAt(0)
                    persistChatState()
                    onVoiceReply(errorReply)
                } finally {
                    isSending = false
                    isSendingLocalModel = false
                    flushPendingWakeWordContextTurns()
                }
            }
            return
        }

        if (!isOnlineConfigured) {
            val offlineReply =
                "Tôi đã nhận tin nhắn. Lệnh điều khiển ứng dụng vẫn xử lý tại máy; để trò chuyện tự do ngoại tuyến, hãy tải mô hình Qwen3-0.6B trong phần NGỌC SĨ AI. Tin nhắn này chưa được gửi ra ngoài và không phát sinh phí AI."
            messages.add(AiPreviewMessage(false, offlineReply))
            if (messages.size > MAX_SAVED_AI_UI_MESSAGES) messages.removeAt(0)
            persistChatState()
            onVoiceReply(offlineReply)
            return
        }

        val userTurn = NgocSiAiMessage(role = "user", content = clean)
        conversation.add(userTurn)
        while (conversation.size > NGOC_SI_AI_MAX_HISTORY_MESSAGES) {
            conversation.removeAt(0)
            if (conversation.firstOrNull()?.role == "assistant") conversation.removeAt(0)
        }
        // Persist the visible user message immediately, but only persist API context
        // after a successful reply so interrupted requests cannot create broken turns.
        saveAiPreviewMessages(chatPreferences, messages)
        var history = conversation.takeLast(NGOC_SI_AI_MAX_HISTORY_MESSAGES)
        if (history.firstOrNull()?.role == "assistant") history = history.drop(1)
        isSendingLocalModel = false
        isSending = true
        coroutineScope.launch {
            var partialReplyIndex = -1
            var latestPartialReply = ""

            fun finishRemoteReply(replyText: String) {
                if (
                    partialReplyIndex in messages.indices &&
                    !messages[partialReplyIndex].isUser &&
                    messages[partialReplyIndex].text == latestPartialReply
                ) {
                    messages[partialReplyIndex] = AiPreviewMessage(false, replyText)
                } else {
                    messages.add(AiPreviewMessage(false, replyText))
                }
                while (messages.size > MAX_SAVED_AI_UI_MESSAGES) messages.removeAt(0)
                partialReplyIndex = -1
                latestPartialReply = ""
            }

            try {
                val videoCapabilityDiagnosticRequested =
                    isNgocSiAiVideoCapabilityDiagnosticRequest(clean)
                val forceMultimodalReasoning =
                    isNgocSiAiYoutubeMultimodalAnalysisRequest(entryContext, clean, history) ||
                        videoCapabilityDiagnosticRequested
                val requestTimeoutMillis = selectNgocSiAiRemoteTimeoutMillis(
                    clean,
                    forceMultimodalReasoning = forceMultimodalReasoning
                )
                val reply = kotlinx.coroutines.withTimeoutOrNull(
                    requestTimeoutMillis
                ) {
                    onSendOnline(history.toList(), onExecuteAppCommand) { partial ->
                        if (partial.isNotBlank()) {
                            if (
                                partialReplyIndex in messages.indices &&
                                !messages[partialReplyIndex].isUser &&
                                messages[partialReplyIndex].text == latestPartialReply
                            ) {
                                messages[partialReplyIndex] = AiPreviewMessage(false, partial)
                            } else {
                                messages.add(AiPreviewMessage(false, partial))
                                partialReplyIndex = messages.lastIndex
                            }
                            latestPartialReply = partial
                        }
                    }
                } ?: throw NgocSiAiRemoteException(
                    ngocSiAiRemoteTimeoutMessage(
                        clean,
                        forceMultimodalReasoning = forceMultimodalReasoning,
                        hasEmittedPartialResponse = latestPartialReply.isNotBlank()
                    )
                )
                conversation.add(NgocSiAiMessage(role = "assistant", content = reply.answer))
                finishRemoteReply(reply.answer)
                persistChatState()
                onVoiceReply(reply.answer)
                val userRemaining = reply.remainingToday?.let { "Còn $it lượt/tài khoản hôm nay" }
                val globalRemaining = reply.remainingGlobalToday?.let { "Còn $it lượt toàn hệ thống hôm nay" }
                quotaNote = listOfNotNull(userRemaining, globalRemaining).joinToString(" • ")
            } catch (cancelled: CancellationException) {
                if (conversation.lastOrNull() == userTurn) conversation.removeAt(conversation.lastIndex)
                if (
                    partialReplyIndex in messages.indices &&
                    !messages[partialReplyIndex].isUser &&
                    messages[partialReplyIndex].text == latestPartialReply
                ) {
                    messages.removeAt(partialReplyIndex)
                }
                persistChatState()
                throw cancelled
            } catch (error: NgocSiAiRemoteException) {
                if (conversation.lastOrNull() == userTurn) conversation.removeAt(conversation.lastIndex)
                val errorReply = error.message ?: "Không thể kết nối NGỌC SĨ AI. Vui lòng thử lại."
                // The sample test must expose the real Firebase result, not an
                // unrelated conversational fallback based on the previous greeting.
                val videoCapabilityDiagnosticRequested =
                    isNgocSiAiVideoCapabilityDiagnosticRequest(clean)
                val fallbackReply = if (videoCapabilityDiagnosticRequested) {
                    errorReply
                } else {
                    localConversationFallbackAfterRemoteError(
                        userText = clean,
                        previousAssistantReply = messages.dropLast(1).lastOrNull { !it.isUser }?.text,
                        remoteError = errorReply
                    )
                }
                val requiresMultimodalVideoAnalysis =
                    isNgocSiAiYoutubeMultimodalAnalysisRequest(
                        pageContext = entryContext,
                        prompt = clean,
                        previousMessages = conversation.toList()
                    ) || isNgocSiAiVideoCapabilityDiagnosticRequest(clean)
                // Qwen3-0.6B is text-only. Never route either the active YouTube video
                // or the MP4 canary to it as if it could inspect audio/video evidence.
                val localModelReply = if (
                    localModelInstalled &&
                    latestPartialReply.isBlank() &&
                    !localModelNeedsAppTools &&
                    !requiresMultimodalVideoAnalysis
                ) {
                    isSendingLocalModel = true
                    runCatching { onGenerateLocalModel(clean, conversation.toList()) }.getOrNull()
                } else null
                val completedLocalAnswer = localModelReply ?: fallbackReply?.takeIf {
                    shouldPersistNgocSiAiOfflineFallbackTurn(
                        fallbackReply = it,
                        hasEmittedPartialResponse = latestPartialReply.isNotBlank(),
                        isToolRequest = localModelNeedsAppTools,
                        requiresMultimodalAnalysis = requiresMultimodalVideoAnalysis
                    )
                }
                if (completedLocalAnswer != null) {
                    val completedHistory = appendCompletedNgocSiAiTurn(
                        history = conversation.toList(),
                        userText = clean,
                        assistantText = completedLocalAnswer
                    )
                    conversation.clear()
                    conversation.addAll(completedHistory)
                }
                val visibleReply = latestPartialReply.trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { partial -> "$partial\n\n$errorReply" }
                    ?: localModelReply
                    ?: if (videoCapabilityDiagnosticRequested) {
                        // Show the actual sample-test failure; do not replace it with a
                        // misleading notice about the currently playing YouTube URL.
                        errorReply
                    } else if (requiresMultimodalVideoAnalysis) {
                        if (NGOC_SI_AI_YOUTUBE_VIDEO_CONTEXT_MISSING_CODE in errorReply) {
                            buildNgocSiAiYoutubeVideoUnavailableReply()
                        } else {
                            buildNgocSiAiLocalMediaAnalysisUnavailableReply(
                                errorReply,
                                videoContextAvailable =
                                    normalizeNgocSiAiEntryContext(entryContext) == "YouTube" &&
                                        NGOC_SI_AI_YOUTUBE_VIDEO_CONTEXT_MISSING_CODE !in errorReply
                            )
                        }
                    } else if (localModelNeedsAppTools) {
                        "Gemini đang bị giới hạn. Ứng dụng chưa thực hiện lệnh nhiều bước này; " +
                            "hãy chia thành từng lệnh đơn hoặc thử lại khi Gemini hoạt động."
                    } else {
                        fallbackReply
                    }
                    ?: errorReply

                // Keep the failed analysis request and this explicit limitation in API history.
                // The next natural follow-up can then still be recognized as a YouTube
                // analysis turn and will attach the newly active queue item (for example B).
                if (isNgocSiAiYoutubeMultimodalAnalysisRequest(entryContext, clean, conversation.toList())) {
                    val retainedHistory = appendCompletedNgocSiAiTurn(
                        history = conversation.toList(),
                        userText = clean,
                        assistantText = visibleReply
                    )
                    conversation.clear()
                    conversation.addAll(retainedHistory)
                }
                quotaNote = if (localModelReply != null) {
                    "Gemini đang bị giới hạn · đã trả lời bằng AI tại máy (${onLocalModelBackendStatus()})"
                } else {
                    ngocSiAiQuotaUiNotice(
                        errorReply,
                        localFallbackUsed = fallbackReply != null
                    )
                }
                finishRemoteReply(visibleReply)
                persistChatState()
                onVoiceReply(visibleReply)
            } catch (_: Exception) {
                if (conversation.lastOrNull() == userTurn) conversation.removeAt(conversation.lastIndex)
                val errorReply = "Có lỗi khi gửi yêu cầu AI. Hãy kiểm tra kết nối và cấu hình Firebase."
                finishRemoteReply(errorReply)
                persistChatState()
                onVoiceReply(errorReply)
            } finally {
                isSending = false
                isSendingLocalModel = false
                flushPendingWakeWordContextTurns()
            }
        }
    }

    LaunchedEffect(recognizedSpeech, recognizedSpeechShouldSend) {
        if (recognizedSpeech.isNotBlank()) {
            val phrase = recognizedSpeech.trim()
            val shouldSubmitRecognizedSpeech = recognizedSpeechShouldSend
            onSpeechConsumed()
            if (shouldSubmitRecognizedSpeech) {
                // Tap-to-talk is an explicit user action: execute only allowlisted local
                // playback commands, or send the question online when configured.
                sendMessage(phrase)
            } else {
                input = listOf(input.trim(), phrase)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .take(2_000)
            }
        }
    }

    if (showVoicePicker) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showVoicePicker = false },
            title = { Text("Chọn giọng đọc của NGỌC SĨ AI") },
            text = {
                Column(Modifier.heightIn(max = 360.dp)) {
                    if (voiceOptions.isEmpty()) {
                        Text(
                            "Điện thoại chưa cung cấp danh sách giọng đọc. Bạn có thể mở phần quản lý giọng đọc để cài thêm.",
                            color = NgocSiVisuals.TextSecondary,
                            fontSize = 13.sp
                        )
                    } else {
                        LazyColumn {
                            items(voiceOptions, key = { it.name }) { voice ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onVoiceSelected(voice.name)
                                            showVoicePicker = false
                                        }
                                        .padding(vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            voice.label,
                                            color = Color.White,
                                            fontSize = 13.sp
                                        )
                                        if (voice.languageTag.startsWith("vi")) {
                                            Text(
                                                "Ưu tiên cho tiếng Việt",
                                                color = Color(0xFF71E6B2),
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                    androidx.compose.material3.RadioButton(
                                        selected = selectedVoiceName == voice.name,
                                        onClick = {
                                            onVoiceSelected(voice.name)
                                            showVoicePicker = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showVoicePicker = false }) {
                    Text("Đóng", color = NgocSiVisuals.TextSecondary)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = onManageVoices) {
                    Text("Quản lý giọng đọc", color = NgocSiVisuals.Secondary)
                }
            },
            containerColor = NgocSiVisuals.SurfaceSoft,
            titleContentColor = Color.White,
            textContentColor = NgocSiVisuals.TextSecondary
        )
    }

    if (showClearConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Xóa lịch sử chat?") },
            text = {
                Text(
                    "Lịch sử chat được lưu trên điện thoại này. Xóa sẽ gỡ các tin nhắn đã lưu và bắt đầu cuộc trò chuyện mới."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        messages.clear()
                        messages.add(defaultAiGreeting(isOnlineConfigured))
                        conversation.clear()
                        input = ""
                        quotaNote = ""
                        persistChatState()
                        showClearConfirm = false
                    }
                ) {
                    Text("Xóa", color = Color(0xFFFFA0A0))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showClearConfirm = false }) {
                    Text("Hủy", color = NgocSiVisuals.TextSecondary)
                }
            },
            containerColor = NgocSiVisuals.SurfaceSoft,
            titleContentColor = Color.White,
            textContentColor = NgocSiVisuals.TextSecondary
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NgocSiVisuals.Background)
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(NgocSiVisuals.Surface)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier
                    .size(44.dp)
                    .semantics { contentDescription = "Quay lại" }
                    .clickable(onClick = onBack),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF1A1E2A)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("‹", color = Color.White, fontSize = 30.sp)
                }
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "NGỌC SĨ AI",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    "TRỢ LÝ CỦA BẠN",
                    color = NgocSiVisuals.Secondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.3.sp
                )
            }
            Surface(
                modifier = Modifier.clickable(enabled = !isSending) {
                    showClearConfirm = true
                },
                shape = RoundedCornerShape(50),
                color = NgocSiVisuals.SurfaceRaised,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3A334A))
            ) {
                Text(
                    "XÓA",
                    color = Color(0xFFFFB0B0),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
            Spacer(Modifier.width(5.dp))
            Surface(
                modifier = Modifier
                    .semantics {
                        contentDescription = if (voiceRepliesEnabled) {
                            "Tắt đọc phản hồi bằng giọng nói"
                        } else {
                            "Bật đọc phản hồi bằng giọng nói"
                        }
                    }
                    .clickable(onClick = onToggleVoiceReplies),
                shape = RoundedCornerShape(50),
                color = if (voiceRepliesEnabled) Color(0xFF123329) else NgocSiVisuals.SurfaceRaised,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (voiceRepliesEnabled) Color(0xFF39C98A) else Color(0xFF3A334A)
                )
            ) {
                Text(
                    if (voiceRepliesEnabled) "🔊" else "🔇",
                    color = if (voiceRepliesEnabled) Color(0xFF71E6B2) else NgocSiVisuals.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
        }

        val selectedVoiceLabel = voiceOptions.firstOrNull { it.name == selectedVoiceName }?.label
            ?: "Giọng mặc định của điện thoại"

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                modifier = Modifier
                    .weight(1.08f)
                    .clickable(onClick = onToggleWakeWord),
                shape = RoundedCornerShape(18.dp),
                color = if (isWakeWordListening) Color(0xFF123329) else NgocSiVisuals.SurfaceRaised,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isWakeWordListening) Color(0xFF286653) else NgocSiVisuals.Border
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(34.dp),
                        shape = CircleShape,
                        color = if (isWakeWordListening) Color(0xFF1B4A3C) else Color(0xFF28213D)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                if (isWakeWordListening) "●" else "◉",
                                color = if (isWakeWordListening) NgocSiVisuals.Success else NgocSiVisuals.Primary,
                                fontSize = 17.sp
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "ĐIỀU KHIỂN GIỌNG NÓI",
                            color = NgocSiVisuals.TextMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.35.sp,
                            maxLines = 1
                        )
                        Text(
                            if (isWakeWordListening) "Đang nghe từ khóa" else "Đánh thức “AI”",
                            color = NgocSiVisuals.Text,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .weight(0.92f)
                    .clickable { showVoicePicker = true },
                shape = RoundedCornerShape(18.dp),
                color = NgocSiVisuals.SurfaceRaised,
                border = androidx.compose.foundation.BorderStroke(1.dp, NgocSiVisuals.Border)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(34.dp),
                        shape = CircleShape,
                        color = Color(0xFF28213D)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("♫", color = NgocSiVisuals.Primary, fontSize = 19.sp)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "GIỌNG ĐỌC",
                            color = NgocSiVisuals.TextMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.35.sp
                        )
                        Text(
                            selectedVoiceLabel,
                            color = NgocSiVisuals.Text,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text("›", color = NgocSiVisuals.Secondary, fontSize = 22.sp)
                }
            }
        }


        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 3.dp),
            shape = RoundedCornerShape(14.dp),
            color = NgocSiVisuals.Surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, NgocSiVisuals.Border)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (isOnlineConfigured) NgocSiVisuals.Success else NgocSiVisuals.TextMuted)
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            localModelInstalled && preferLocalModel -> "NGỌC SĨ AI • CHẾ ĐỘ TẠI MÁY"
                            localModelInstalled && isOnlineConfigured -> "NGỌC SĨ AI • GEMINI + QWEN3 DỰ PHÒNG"
                            isOnlineConfigured -> "NGỌC SĨ AI • ĐÃ CẤU HÌNH GEMINI"
                            else -> "NGỌC SĨ AI • CHẾ ĐỘ CỤC BỘ"
                        },
                        color = NgocSiVisuals.Text,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        when {
                            quotaNote.isNotBlank() -> quotaNote
                            localModelInstalled && preferLocalModel ->
                                "Ưu tiên mô hình AI chạy trên điện thoại; câu hỏi tự do không gửi lên Gemini."
                            localModelInstalled && isOnlineConfigured ->
                                "Gemini là chế độ chính; Qwen3 tại máy sẽ dự phòng khi Gemini hết hạn mức."
                            localModelInstalled ->
                                "Mô hình Qwen3 đã sẵn sàng; chat có thể chạy ngoại tuyến."
                            isOnlineConfigured ->
                                "Đã nạp cấu hình Gemini; trạng thái trực tuyến chỉ được xác nhận sau khi Gemini trả lời thành công."
                            else ->
                                "Lệnh nhạc cơ bản chạy tại máy; tải mô hình để bật chat ngoại tuyến."
                        },
                        color = NgocSiVisuals.TextSecondary,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = !localModelDownloading && !localModelInstalled) {
                        localModelDownloading = true
                        localModelDownloadedBytes = 0L
                        localModelDownloadError = ""
                        coroutineScope.launch {
                            try {
                                onDownloadLocalModel { downloaded, total ->
                                    coroutineScope.launch(Dispatchers.Main.immediate) {
                                        localModelDownloadedBytes = downloaded
                                        localModelDownloadTotalBytes = total
                                    }
                                }
                                localModelInstalled = onIsLocalModelInstalled()
                                localModelDownloadedBytes = localModelDownloadTotalBytes
                            } catch (error: Exception) {
                                localModelDownloadError = error.message
                                    ?: "Không tải được mô hình AI ngoại tuyến."
                            } finally {
                                localModelDownloading = false
                            }
                        }
                    },
                shape = RoundedCornerShape(12.dp),
                color = if (localModelInstalled) Color(0xFF123329) else NgocSiVisuals.SurfaceRaised,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (localModelInstalled) Color(0xFF286653) else NgocSiVisuals.Border
                )
            ) {
                Text(
                    when {
                        localModelDownloading -> "ĐANG TẢI AI NGOẠI TUYẾN · " +
                            "${if (localModelDownloadTotalBytes > 0L) (localModelDownloadedBytes * 100L / localModelDownloadTotalBytes).toInt() else 0}%"
                        localModelInstalled -> "AI TẠI MÁY ĐÃ SẴN SÀNG"
                        else -> "TẢI AI NGOẠI TUYẾN · ${NgocSiAiOnDeviceModel.MODEL_SIZE_LABEL}"
                    },
                    color = if (localModelInstalled) Color(0xFF71E6B2) else NgocSiVisuals.Text,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                )
            }
            if (localModelInstalled || preferLocalModel) {
                Surface(
                    modifier = Modifier.clickable {
                        val nextPreferLocal = !preferLocalModel
                        preferLocalModel = nextPreferLocal
                        chatPreferences.edit()
                            .putBoolean(NGOC_SI_AI_PREFER_LOCAL_MODEL_KEY, nextPreferLocal)
                            .apply()
                        if (!nextPreferLocal) {
                            coroutineScope.launch {
                                runCatching { onReleaseLocalModel() }
                                    .onFailure { error ->
                                        quotaNote = "Đã chuyển sang Gemini; chưa giải phóng được bộ nhớ AI tại máy: " +
                                            (error.message ?: "lỗi không xác định")
                                    }
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = if (preferLocalModel) Color(0xFF28213D) else NgocSiVisuals.SurfaceRaised,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (preferLocalModel) NgocSiVisuals.Primary else NgocSiVisuals.Border
                    )
                ) {
                    Text(
                        if (preferLocalModel) "ƯU TIÊN AI TẠI MÁY" else "GEMINI · DỰ PHÒNG MÁY",
                        color = if (preferLocalModel) NgocSiVisuals.Primary else NgocSiVisuals.TextSecondary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 10.dp)
                    )
                }
            }
        }
        if (localModelDownloadError.isNotBlank()) {
            Text(
                localModelDownloadError,
                color = Color(0xFFFFB0B0),
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
            )
        }

        LazyColumn(
            state = messageListState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(messages) { message ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
                ) {
                    if (!message.isUser) {
                        Surface(
                            modifier = Modifier.size(34.dp),
                            shape = RoundedCornerShape(11.dp),
                            color = Color(0xFF29203F)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("✦", color = NgocSiVisuals.Primary, fontSize = 17.sp)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Column(modifier = Modifier.fillMaxWidth(0.84f)) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(
                                topStart = 18.dp,
                                topEnd = 18.dp,
                                bottomStart = if (message.isUser) 18.dp else 5.dp,
                                bottomEnd = if (message.isUser) 5.dp else 18.dp
                            ),
                            color = if (message.isUser) Color(0xFF25213A) else NgocSiVisuals.SurfaceRaised,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (message.isUser) Color(0xFF44376B) else NgocSiVisuals.Border
                            )
                        ) {
                            Text(
                                message.text,
                                modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
                                color = if (message.isUser) Color(0xFFF0ECFF) else NgocSiVisuals.TextSecondary,
                                fontSize = 14.sp,
                                lineHeight = 21.sp
                            )
                        }
                        if (!message.isUser) {
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Surface(
                                    modifier = Modifier.clickable { onSpeakMessage(message.text) },
                                    shape = RoundedCornerShape(50),
                                    color = NgocSiVisuals.SurfaceSoft,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2B3040))
                                ) {
                                    Text(
                                        "🔊 ĐỌC",
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                        color = NgocSiVisuals.TextSecondary,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (isSending) {
                item(key = "assistant-thinking") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(32.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF28213D)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("✦", color = NgocSiVisuals.Primary, fontSize = 17.sp)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = NgocSiVisuals.SurfaceRaised,
                            border = androidx.compose.foundation.BorderStroke(1.dp, NgocSiVisuals.Border)
                        ) {
                            Text(
                                "Đang xử lý yêu cầu…",
                                modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                                color = NgocSiVisuals.TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D1016))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            if (isVoiceListening) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF17231F),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2C5148))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🎙", fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (partialSpeechTranscript.isBlank()) "ĐANG NGHE TIẾNG VIỆT"
                                else "ĐANG NHẬN GIỌNG NÓI",
                                color = Color(0xFF86E0BD),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                partialSpeechTranscript.ifBlank { "Hãy nói tự nhiên; bản ghi sẽ hiện tại đây." },
                                color = Color(0xFFE0EDE8),
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            Text(
                "GỢI Ý THỬ",
                color = Color(0xFF9299AA),
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(7.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                val suggestions = listOf(
                    "Bạn làm được gì?",
                    "Tôi muốn app của mình chuyên nghiệp hơn nhưng chưa biết nên bắt đầu từ đâu.",
                    "Phát nhạc",
                    "Tạm dừng nhạc",
                    "Mở YouTube",
                    "Bài tiếp theo",
                    "Mở Google Drive",
                    "Mở Radio",
                    "Hẹn giờ 30 phút"
                )
                suggestions.forEach { suggestion ->
                    Surface(
                        modifier = Modifier.clickable { input = suggestion },
                        shape = RoundedCornerShape(50),
                        color = NgocSiVisuals.SurfaceSoft,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2B3040))
                    ) {
                        Text(
                            suggestion,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            color = Color(0xFFE5E2F0),
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(NgocSiVisuals.SurfaceSoft)
                    .border(1.dp, NgocSiVisuals.Border, RoundedCornerShape(20.dp))
                    .padding(start = 13.dp, end = 7.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it.take(2_000) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 10.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color.White,
                        fontSize = 14.sp
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(NgocSiVisuals.Secondary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendMessage(input) }),
                    singleLine = false,
                    decorationBox = { innerTextField ->
                        Box {
                            if (input.isBlank()) {
                                Text(
                                    "Nhập tin nhắn...",
                                    color = Color(0xFF9299AA),
                                    fontSize = 14.sp
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                Surface(
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = "Nhập câu hỏi bằng giọng nói" }
                        .clickable(onClick = onVoice),
                    shape = CircleShape,
                    color = NgocSiVisuals.SurfaceRaised
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("🎙", fontSize = 17.sp)
                    }
                }
                Spacer(Modifier.width(6.dp))
                Surface(
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            contentDescription = if (isSending) "Đang gửi tin nhắn" else "Gửi tin nhắn"
                        }
                        .clickable(enabled = !isSending) { sendMessage(input) },
                    shape = CircleShape,
                    color = if (isSending) Color(0xFF515661) else NgocSiVisuals.Secondary
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(if (isSending) "…" else "↑", color = Color(0xFF061018), fontSize = 22.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(
                when {
                    isSending && isSendingLocalModel -> "Đang xử lý ngay trên điện thoại · không gửi lên Gemini…"
                    isSending -> "Đang chờ phản hồi từ Gemini…"
                    isOnlineConfigured -> quotaNote.ifBlank { "Nhấn GỬI để hỏi, hoặc dùng micro để gửi câu hỏi tự động sau khi nhận dạng." }
                    else -> "Bản xem trước chạy cục bộ; lịch sử được lưu trên điện thoại."
                },
                color = Color(0xFF9299AA),
                fontSize = 10.sp
            )
        }
    }
}