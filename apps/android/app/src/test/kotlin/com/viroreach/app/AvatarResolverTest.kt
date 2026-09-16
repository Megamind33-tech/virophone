package com.viroreach.app

import com.viroreach.app.personalization.AvatarRequest
import com.viroreach.app.personalization.AvatarResolver
import com.viroreach.app.personalization.AvatarSourceKind
import org.junit.Assert.*
import org.junit.Test

class AvatarResolverTest {
    @Test
    fun viroProfilePhotoTakesPrecedence() {
        val resolved = AvatarResolver.resolve(
            AvatarRequest(
                displayName = "Brian",
                viroProfilePhotoUrl = "https://reach.viro3.online/avatar/brian.jpg",
                localContactPhotoUri = "content://contacts/1",
            ),
        )
        assertEquals(AvatarSourceKind.VIRO_PROFILE, resolved.kind)
        assertTrue(resolved.imageUrl!!.contains("avatar"))
    }

    @Test
    fun localContactPhotoUsedWhenNoViroPhoto() {
        val resolved = AvatarResolver.resolve(
            AvatarRequest(
                displayName = "Alice",
                localContactPhotoUri = "content://contacts/2",
            ),
        )
        assertEquals(AvatarSourceKind.LOCAL_CONTACT, resolved.kind)
    }

    @Test
    fun noPhotoUsesInitialsMetadata_onlyNotPlusDigits() {
        val resolved = AvatarResolver.resolve(AvatarRequest(displayName = "+260961582985"))
        assertEquals(AvatarSourceKind.INITIALS, resolved.kind)
        assertFalse(resolved.initials.startsWith("+"))
    }

    @Test
    fun nameInitials_forRealNames() {
        val resolved = AvatarResolver.resolve(AvatarRequest(displayName = "Brian Smith"))
        assertEquals("BS", resolved.initials)
    }
}
