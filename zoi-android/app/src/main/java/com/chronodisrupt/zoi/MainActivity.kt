
package com.chronodisrupt.zoi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class ZoiState {
    IDLE, LISTENING, THINKING, SPEAKING
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                ZoiScreen()
            }
        }
    }
}

@Composable
fun ZoiScreen() {
    var state by remember { mutableStateOf(ZoiState.IDLE) }

    val transition = rememberInfiniteTransition(label = "zoi")

    // Eye movement
    val eyeShift by transition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (state) {
                    ZoiState.IDLE -> 2200
                    ZoiState.LISTENING -> 1200
                    ZoiState.THINKING -> 500
                    ZoiState.SPEAKING -> 1800
                }
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eye movement"
    )

    // Listening eye pulse
    val eyeHeight by transition.animateFloat(
        initialValue = 25f,
        targetValue = 32f,
        animationSpec = infiniteRepeatable(
            animation = tween(650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eye pulse"
    )

    // Speaking mouth animation
    val mouthWidth by transition.animateFloat(
        initialValue = 24f,
        targetValue = 65f,
        animationSpec = infiniteRepeatable(
            animation = tween(350),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mouth movement"
    )

    val accent = when (state) {
        ZoiState.IDLE -> Color(0xFF62D9FF)
        ZoiState.LISTENING -> Color(0xFF58E6B5)
        ZoiState.THINKING -> Color(0xFFB49AFF)
        ZoiState.SPEAKING -> Color(0xFFFFC36B)
    }

    val status = when (state) {
        ZoiState.IDLE -> "READY WHEN YOU ARE"
        ZoiState.LISTENING -> "LISTENING"
        ZoiState.THINKING -> "THINKING"
        ZoiState.SPEAKING -> "SPEAKING"
    }

    val message = when (state) {
        ZoiState.IDLE -> "I'm here."
        ZoiState.LISTENING -> "I'm listening."
        ZoiState.THINKING -> "Give me a moment."
        ZoiState.SPEAKING -> "Here's what I think."
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0D12))
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "ZOI",
                color = Color.White,
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 5.sp
            )

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "PERSONAL COMPANION",
                color = Color(0xFF777E8D),
                fontSize = 9.sp,
                letterSpacing = 2.sp
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Robot face
        Box(
            modifier = Modifier.size(260.dp),
            contentAlignment = Alignment.Center
        ) {

            // Outer glow
            Box(
                modifier = Modifier
                    .size(250.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.045f))
            )

            // Robot head
            Column(
                modifier = Modifier
                    .width(218.dp)
                    .height(178.dp)
                    .clip(RoundedCornerShape(48.dp))
                    .background(Color(0xFF171C26))
                    .border(
                        width = 2.dp,
                        color = accent.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(48.dp)
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {

                // Eyes
                Row(
                    modifier = Modifier.offset(x = eyeShift.dp),
                    horizontalArrangement = Arrangement.spacedBy(38.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(2) {
                        Box(
                            modifier = Modifier
                                .size(
                                    width = 29.dp,
                                    height = if (state == ZoiState.LISTENING) {
                                        eyeHeight.dp
                                    } else {
                                        29.dp
                                    }
                                )
                                .clip(CircleShape)
                                .background(accent)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(27.dp))

                // Mouth
                Box(
                    modifier = Modifier
                        .width(
                            if (state == ZoiState.SPEAKING) {
                                mouthWidth.dp
                            } else {
                                38.dp
                            }
                        )
                        .height(
                            if (state == ZoiState.SPEAKING) 8.dp else 5.dp
                        )
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.8f))
                )
            }
        }

        Spacer(modifier = Modifier.height(42.dp))

        // Status
        Text(
            text = status,
            color = accent,
            fontSize = 11.sp,
            letterSpacing = 3.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = message,
            color = Color.White,
            fontSize = 23.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.weight(1f))

        // Preview interaction
        Button(
            onClick = {
                state = when (state) {
                    ZoiState.IDLE -> ZoiState.LISTENING
                    ZoiState.LISTENING -> ZoiState.THINKING
                    ZoiState.THINKING -> ZoiState.SPEAKING
                    ZoiState.SPEAKING -> ZoiState.IDLE
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF202B38),
                contentColor = accent
            )
        ) {
            Text(
                text = if (state == ZoiState.IDLE) {
                    "PREVIEW INTERACTION"
                } else {
                    "NEXT STATE"
                },
                fontSize = 12.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "INTERFACE PREVIEW • VOICE NOT CONNECTED",
            color = Color(0xFF555D6B),
            fontSize = 9.sp,
            letterSpacing = 1.5.sp
        )

        Spacer(modifier = Modifier.height(18.dp))
    }
}
