package com.redfluent.vpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redfluent.vpn.ui.theme.Brand
import com.redfluent.vpn.ui.theme.Radius
import com.redfluent.vpn.ui.theme.Spacing

/** 品牌渐变背景，与 iOS 版一致：左上 brandPrimary → 右下 brandSecondary。 */
private val BrandGradient = Brush.linearGradient(listOf(Brand.Primary, Brand.Secondary))

private data class Slide(val icon: ImageVector, val title: String, val body: String)

private val slides = listOf(
    Slide(
        Icons.Filled.Send,
        "RedFluent VPN",
        "A private secure tunnel built for trusted devices. Not a public service.",
    ),
    Slide(
        Icons.Filled.VerifiedUser,
        "Invite only",
        "Each device gets its own revocable profile. No public signup, no advertising.",
    ),
    Slide(
        Icons.Filled.Lock,
        "Privacy first",
        "Your traffic is encrypted between this device and RedFluent infrastructure. We do not sell data.",
    ),
)

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    val slide = slides[page]

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandGradient),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .size(180.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    slide.icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(72.dp)
                        .then(if (page == 0) Modifier.rotate(-30f) else Modifier),
                )
            }

            Spacer(Modifier.height(Spacing.lg))
            Text(
                slide.title,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Spacer(Modifier.height(Spacing.md))
            Text(
                slide.body,
                fontSize = 17.sp,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Spacing.xl),
            )

            Spacer(Modifier.weight(1f))

            // 页码指示
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.padding(bottom = Spacing.lg),
            ) {
                slides.indices.forEach { i ->
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = if (i == page) 1f else 0.35f)),
                    )
                }
            }

            Button(
                onClick = { if (page < slides.lastIndex) page++ else onDone() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Spacing.xl),
                shape = RoundedCornerShape(Radius.md),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.2f),
                    contentColor = Color.White,
                ),
            ) {
                Text(
                    if (page < slides.lastIndex) "Next" else "Get started",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = Spacing.sm),
                )
            }
        }
    }
}

@Composable
fun ActivationScreen(
    activating: Boolean,
    error: String?,
    onActivate: (String) -> Unit,
    /** 设备被 owner 登出后的一句话提示（平静的提示，不是错误）。 */
    notice: String? = null,
) {
    var code by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandGradient),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(Spacing.xxl))

            // RF 标志：96dp 圆角方块
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(Radius.lg))
                    .background(Color.White.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("RF", fontSize = 38.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            Spacer(Modifier.height(Spacing.lg))
            Text("RedFluent VPN", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(Spacing.sm))
            Text(
                "Private secure tunnel for trusted devices",
                fontSize = 17.sp,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(Spacing.xl))

            // 激活卡片
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.md))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(Spacing.md),
            ) {
                if (notice != null) {
                    Text(
                        notice,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(Brand.Primary.copy(alpha = 0.10f))
                            .padding(Spacing.sm),
                    )
                    Spacer(Modifier.height(Spacing.md))
                }
                Text(
                    "Enter your invite code",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "RedFluent VPN is private invite-only software. " +
                        "Each device receives its own revocable profile.",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.md))

                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    placeholder = { Text("Invite code") },
                    singleLine = true,
                    enabled = !activating,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { onActivate(code) }),
                    shape = RoundedCornerShape(Radius.sm),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (error != null) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(error, fontSize = 13.sp, color = Brand.Danger)
                }

                Spacer(Modifier.height(Spacing.md))
                Button(
                    onClick = { onActivate(code) },
                    enabled = !activating && code.isNotBlank(),
                    shape = RoundedCornerShape(Radius.md),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Primary),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (activating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Activate", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = Spacing.xs))
                    }
                }
            }

            Spacer(Modifier.height(Spacing.lg))
            Text(
                "We do not sell or share your data.",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}
