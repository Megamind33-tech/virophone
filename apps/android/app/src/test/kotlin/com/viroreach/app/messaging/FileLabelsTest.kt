package com.viroreach.app.messaging

import com.viroreach.app.messaging.ui.fileKindLabel
import com.viroreach.app.messaging.ui.formatFileSize
import org.junit.Assert.assertEquals
import org.junit.Test

class FileLabelsTest {
    @Test
    fun `file sizes read the way people say them`() {
        assertEquals("", formatFileSize(null))
        assertEquals("0 B", formatFileSize(0))
        assertEquals("999 B", formatFileSize(999))
        assertEquals("1 KB", formatFileSize(1024))
        assertEquals("340 KB", formatFileSize(348_160))
        assertEquals("1.2 MB", formatFileSize(1_258_291))
        assertEquals("25 MB", formatFileSize(26_214_400))
    }

    @Test
    fun `documents are named by what they are`() {
        assertEquals("PDF", fileKindLabel("Invoice March.pdf", "application/pdf"))
        assertEquals("PDF", fileKindLabel(null, "application/pdf"))
        assertEquals("Document", fileKindLabel("Notes.docx", null))
        assertEquals("Spreadsheet", fileKindLabel("Budget.xlsx", null))
        assertEquals("Presentation", fileKindLabel("Pitch.pptx", null))
        assertEquals("Archive", fileKindLabel("photos.zip", null))
        assertEquals("Video", fileKindLabel("clip.mov", "video/quicktime"))
        // An unknown extension is still better than a bare "File".
        assertEquals("DWG", fileKindLabel("plan.dwg", null))
        assertEquals("File", fileKindLabel("noextension", null))
        assertEquals("File", fileKindLabel(null, null))
    }
}
