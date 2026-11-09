package com.zepinto.kraken

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Palette {
    val Ink = Color(0xFF0B1030)
    val Night = Color(0xFF1B2250)
    val Red = Color(0xFFD6363B)
    val Blue = Color(0xFF2F6BE0)
    val Sand = Color(0xFFC9B58A)
    val Kraken = Color(0xFF15151A)
    val Paper = Color(0xFFF1E9D7)
    val PaperText = Color(0xFF2B2438)
    val Sun = Color(0xFFFFD166)
    val Aqua = Color(0xFF5CE1E6)
    val Mint = Color(0xFF6EE7B7)
    val Lilac = Color(0xFFB69CFF)
    val Treasure = Color(0xFF2E9E5B)
    val TreasureDeep = Color(0xFF1B6B3E)

    fun team(team: Team) = if (team == Team.RED) Red else Blue
    fun card(type: CardType) = when (type) {
        CardType.RED -> Red
        CardType.BLUE -> Blue
        CardType.NEUTRAL -> Sand
        CardType.KRAKEN -> Kraken
    }
}

val Pill = RoundedCornerShape(50)
val Soft = RoundedCornerShape(22.dp)

/** Deep navy gradient with soft blobs, behind every screen. */
fun Modifier.appBackground(): Modifier = this
    .background(Brush.verticalGradient(listOf(Palette.Ink, Palette.Night)))
    .drawBehind {
        drawCircle(Palette.Red.copy(alpha = 0.16f), radius = size.width * 0.55f, center = Offset(size.width * 0.05f, size.height * 0.02f))
        drawCircle(Palette.Blue.copy(alpha = 0.18f), radius = size.width * 0.6f, center = Offset(size.width * 0.98f, size.height * 0.98f))
    }

/**
 * Stripes for red and dots for blue, so the two teams can be told apart without seeing colour.
 * Drawn on top of the card's fill.
 */
fun Modifier.typePattern(type: CardType?): Modifier = drawBehind {
    val mark = Color.White.copy(alpha = 0.28f)
    when (type) {
        CardType.RED -> {
            val step = 12.dp.toPx()
            var x = -size.height
            while (x < size.width) {
                drawLine(mark, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 2.5.dp.toPx())
                x += step
            }
        }
        CardType.BLUE -> {
            val step = 11.dp.toPx()
            var y = step / 2
            while (y < size.height) {
                var x = step / 2
                while (x < size.width) {
                    drawCircle(mark, radius = 1.8.dp.toPx(), center = Offset(x, y))
                    x += step
                }
                y += step
            }
        }
        else -> Unit
    }
}

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Palette.Sun,
    content: Color = Palette.Ink,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = Pill,
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = content,
            disabledContainerColor = Color.White.copy(alpha = 0.12f),
            disabledContentColor = Color.White.copy(alpha = 0.4f),
        ),
        modifier = modifier.heightIn(min = 52.dp),
    ) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold) }
}

@Composable
fun Chip(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(shape = Pill, color = color.copy(alpha = 0.22f), modifier = modifier) {
        Text(
            text,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
