package com.nexus.client.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

// ============================================================================
// Ex WG Warm-Stone & Milk-Glassmorphic Design Tokens (neXus Ray Aesthetic)
// ============================================================================

// Base warm stone surfaces
val NexusBackground = Color(0xFFC6B8A5) // Warm stone base
val NexusSurface = Color(0xFFFAF8F4) // Frosted milk-glass primary surface
val NexusSurfaceVariant = Color(0xFFF3EFE8) // Warm sand sub-card surface
val ExWgRaised = Color(0xFFFFFFFF) // Crisp white raised pill
val ExWgOutline = Color(0xFFD7CFC2) // Subtle warm border
val ExWgCardBorder = Color(0xEBFFFFFF) // Crisp white specular card border

// High-contrast typography tokens
val ExWgOnSurface = Color(0xFF17181A) // Dark charcoal primary text (15.2:1 AAA contrast)
val ExWgMuted = Color(0xFF6E6B65) // Warm muted secondary text (4.95:1 AA contrast)

// Signature emerald-teal interactive tokens
val NexusPrimary = Color(0xFF197B6E) // Signature emerald-teal accent
val NexusPrimaryVariant = Color(0xFF115E54) // Deep emerald accent
val NexusTealBright = Color(0xFF2FA899) // Bright teal accent

// Status tokens
val NexusMint = Color(0xFF136952) // Deep mint status text (5.56:1 AA contrast on light surfaces, resolves white-on-white defect)
val NexusMintPill = Color(0xFFDDF3EC) // Mint status pill background
val NexusAmber = Color(0xFFD97706) // Accessible amber warning token
val NexusAmberPill = Color(0xFFFFF7E6) // Amber warning pill background
val NexusAmberBorder = Color(0xFFFFCA78) // Amber warning border

// Ambient multi-stop backdrop colors
val ExStoneTop = Color(0xFFD5C9B8)
val ExStoneMid = Color(0xFFC6B8A5)
val ExStoneBottom = Color(0xFFB7A894)

// Card & container tokens
val ExMilkCard = Color(0xFFFAF8F4)
val ExMilkBorder = Color(0xEBFFFFFF)
val ExSandSubcard = Color(0xFFF3EFE8)
val ExSandBorder = Color(0xFFD7CFC2)
val ExPillBg = Color(0xD9F8F5EF)
val ExPillBorder = Color(0xE6FFFFFF)
val ExTabActive = Color(0xFFFFFFFF)

// Charcoal & emerald control tokens
val ExCharcoal = Color(0xFF17181A)
val ExCharcoalButton = Color(0xFF18191B)
val ExMuted = Color(0xFF6E6B65)
val ExEmerald = Color(0xFF197B6E)
val ExEmeraldLight = Color(0xFF2FA899)
val ExEmeraldDark = Color(0xFF115E54)

// Status badges
val ExMintBg = Color(0xFFDDF3EC)
val ExMintText = Color(0xFF136952)
val ExAmber = Color(0xFFD97706)
val ExAmberBg = Color(0xFFFFF7E6)
val ExAmberBorder = Color(0xFFFFCA78)
val ExAmberText = Color(0xFF9C6500)
val ExError = Color(0xFFB83B2A)
val ExErrorBg = Color(0xFFFDF2F0)

// Backward-compatibility aliases
val NexusPurple = Color(0xFF6E6B65)
val NexusBlue = Color(0xFF197B6E)

private val colors = lightColorScheme(
    primary = ExCharcoalButton,
    onPrimary = Color.White,
    secondary = NexusPrimary,
    onSecondary = Color.White,
    tertiary = NexusMint,
    onTertiary = Color.White,
    background = NexusBackground,
    onBackground = ExWgOnSurface,
    surface = NexusSurface,
    onSurface = ExWgOnSurface,
    surfaceVariant = NexusSurfaceVariant,
    onSurfaceVariant = ExWgMuted,
    outline = ExWgOutline,
    outlineVariant = Color(0xFFE8E2D8),
    error = ExError,
    onError = Color.White,
)

@Composable
fun NexusTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(
            LocalContentColor provides colors.onSurface,
            content = content
        )
    }
}
