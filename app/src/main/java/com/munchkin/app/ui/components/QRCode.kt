package com.munchkin.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * QR Code display component for game joining.
 */
@Composable
fun QRCodeDisplay(
    content: String,
    modifier: Modifier = Modifier,
    size: Int = 250,
    backgroundColor: Color = Color.White,
    foregroundColor: Color = Color.Black
) {
    val bitmap = remember(content, size, backgroundColor, foregroundColor) {
        generateQRCode(
            content = content,
            size = size,
            backgroundColor = backgroundColor.toArgb(),
            foregroundColor = foregroundColor.toArgb()
        )
    }
    
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "Código QR",
            modifier = modifier
                .size(size.dp)
                .clip(RoundedCornerShape(12.dp))
        )
    }
}

/**
 * Connection info card with QR code and manual entry details.
 */
@Composable
fun ConnectionInfoCard(
    wsUrl: String,
    joinCode: String,
    localIp: String,
    port: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Escanea para unirte",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // QR Code - Contains the full connection URL
        val qrContent = "$wsUrl?code=$joinCode"
        QRCodeDisplay(
            content = qrContent,
            size = 200,
            backgroundColor = Color.White,
            foregroundColor = Color.Black
        )
        
        Spacer(modifier = Modifier.height(20.dp))
        
        // Divider with "o"
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            )
            Text(
                text = "  o introduce manualmente  ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Manual entry info
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ConnectionDetail(label = "IP", value = localIp)
            ConnectionDetail(label = "Puerto", value = port.toString())
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Join code (large and prominent)
        Text(
            text = "Código",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = joinCode,
            style = MaterialTheme.typography.headlineLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp
            ),
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun ConnectionDetail(
    label: String,
    value: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = FontFamily.Monospace
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Generate QR code bitmap using ZXing.
 */
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
    } catch (e: Exception) {
        null
    }
}

