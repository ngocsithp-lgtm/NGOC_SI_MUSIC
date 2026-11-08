package com.ngocsi.music

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NgocSiAiLocalMusicKnowledgeRulesTest {
    @Test
    fun explainsBoleroWithoutCollapsingItsDifferentTraditions() {
        val answer = localNgocSiAiMusicKnowledgeFallback("AI, Bolero là gì?")!!
        assertTrue(answer.contains("Tây Ban Nha"))
        assertTrue(answer.contains("Bolero Cuba"))
        assertTrue(answer.contains("đời sống âm nhạc Việt Nam"))
        assertTrue(answer.contains("Không nên đồng nhất"))
        assertTrue(answer.contains("không phải nhận diện một bài hát cụ thể"))
    }

    @Test
    fun explainsCommonBoleroPerformanceCriteriaOffline() {
        val answer = localNgocSiAiMusicKnowledgeFallback("AI, cách hát Bolero cần chú ý gì?")!!
        assertTrue(answer.contains("Nhịp điệu"))
        assertTrue(answer.contains("Nhả chữ"))
        assertTrue(answer.contains("Luyến láy"))
        assertTrue(answer.contains("Hơi thở"))
        assertTrue(answer.contains("Rung giọng"))
        assertTrue(answer.contains("âm thanh thực tế"))
    }

    @Test
    fun explainsLuyenLaAndNhanChuInVietnamese() {
        val luyen = localNgocSiAiMusicKnowledgeFallback("AI, luyến láy là gì?")!!
        assertTrue(luyen.contains("chuyển động cao độ"))
        assertTrue(luyen.contains("đúng cao độ, đúng nhịp"))

        val diction = localNgocSiAiMusicKnowledgeFallback("AI, nhả chữ là gì?")!!
        assertTrue(diction.contains("nguyên âm, phụ âm"))
        assertTrue(diction.contains("ý nghĩa câu hát"))
    }

    @Test
    fun explainsVibratoAndMelismaWithoutConfusingThem() {
        val vibrato = localNgocSiAiMusicKnowledgeFallback("AI, rung giọng là gì?")!!
        assertTrue(vibrato.contains("vibrato"))
        assertTrue(vibrato.contains("dao động nhỏ và tương đối đều"))
        assertTrue(vibrato.contains("run giọng"))

        val melisma = localNgocSiAiMusicKnowledgeFallback("AI, melisma là gì?")!!
        assertTrue(melisma.contains("nhiều nốt nhạc trên một âm tiết"))
    }

    @Test
    fun explainsBreathManagementAndGenericSingerEvaluation() {
        val breath = localNgocSiAiMusicKnowledgeFallback("AI, kiểm soát hơi thở khi hát thế nào?")!!
        assertTrue(breath.contains("phối hợp luồng hơi"))
        assertTrue(breath.contains("vị trí lấy hơi"))

        val evaluation = localNgocSiAiMusicKnowledgeFallback("AI, tiêu chí đánh giá giọng hát là gì?")!!
        assertTrue(evaluation.contains("Cao độ"))
        assertTrue(evaluation.contains("Nhả chữ"))
        assertTrue(evaluation.contains("điều kiện thu âm tương đương"))
    }

    @Test
    fun doesNotInventEvidenceAboutTheCurrentlyPlayingVideoOrRecording() {
        assertNull(localNgocSiAiMusicKnowledgeFallback("AI, phân tích video Bolero đang phát."))
        assertNull(localNgocSiAiMusicKnowledgeFallback("AI, ca sĩ này đang luyến láy thế nào trong bản thu?"))
        assertNull(localNgocSiAiMusicKnowledgeFallback("AI, rung giọng ở đoạn nhạc này ra sao?"))
    }

    @Test
    fun unrelatedFactsStayOnTheExistingKnowledgePath() {
        assertNull(localNgocSiAiMusicKnowledgeFallback("AI, thủ đô Việt Nam là gì?"))
        assertNull(localNgocSiAiMusicKnowledgeFallback("AI, mở YouTube"))
    }
}
