package com.munchkin.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.munchkin.app.R
import com.munchkin.app.network.ServerConfig
import com.munchkin.app.ui.theme.DungeonBorder
import com.munchkin.app.ui.theme.NeonGray200
import com.munchkin.app.ui.theme.NeonSecondary
import com.munchkin.app.ui.theme.NeonSurfaceVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Generates and displays a crisp QR code bitmap off the main thread.
 */
@Composable
fun QRCodeDisplay(
    content: String,
    modifier: Modifier = Modifier,
    size: Int = 250,
    backgroundColor: Color = Color.White,
    foregroundColor: Color = Color.Black
) {
    val bgArgb = backgroundColor.toArgb()
    val fgArgb = foregroundColor.toArgb()
    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = content,
        key2 = size,
        key3 = bgArgb to fgArgb
    ) {
        value = if (content.isBlank()) {
            null
        } else {
            withContext(Dispatchers.Default) {
                generateQRCode(
                    content = content,
                    size = size,
                    backgroundColor = bgArgb,
                    foregroundColor = fgArgb
                )
            }
        }
    }

    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(shape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        val generated = bitmap
        if (generated != null) {
            Image(
                bitmap = generated.asImageBitmap(),
                contentDescription = stringResource(R.string.scan_to_join),
                modifier = Modifier.fillMaxSize(),
                filterQuality = FilterQuality.None
            )
        } else if (content.isNotBlank()) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.5.dp,
                color = foregroundColor
            )
        }
    }
}

/**
 * Join code card encoding the universal join link for inviting players.
 */
@Composable
fun JoinCodeCard(
    joinCode: String,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)
    val qrPayload = if (joinCode.isNotBlank()) {
        "https://${ServerConfig.HOST}/#/join/$joinCode"
    } else {
        ""
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(NeonSurfaceVariant)
            .border(1.dp, DungeonBorder, shape)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.scan_to_join),
            style = MaterialTheme.typography.titleMedium,
            color = NeonGray200
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (qrPayload.isNotBlank()) {
            QRCodeDisplay(
                content = qrPayload,
                size = 160
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text(
            text = joinCode,
            style = MaterialTheme.typography.displayMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 8.sp
            ),
            color = NeonSecondary
        )
    }
}

private fun generateQRCode(
    content: String,
    size: Int,
    backgroundColor: Int,
    foregroundColor: Int
): Bitmap? {
    return try {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (bitMatrix[x, y]) foregroundColor else backgroundColor
            }
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    } catch (_: Exception) {
        null
    }
}
