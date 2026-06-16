package com.example.expensetracker.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Production login screen with Google Sign-In button.
 *
 * @param isLoading True while the sign-in flow is in progress.
 * @param errorMessage Error message to show on auth failure, or null.
 * @param onSignInClick Callback to trigger the Google sign-in intent.
 */
@Composable
fun LoginScreen(
    isLoading: Boolean,
    errorMessage: String?,
    onSignInClick: () -> Unit
) {
    // Subtle fade-in animation
    val fadeIn = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        fadeIn.animateTo(1f, animationSpec = tween(600, easing = EaseOutCubic))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1A1A2E),
                        Color(0xFF16213E),
                        Color(0xFF0F3460)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .alpha(fadeIn.value),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // App branding
            Text(
                text = "Household",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 1.sp
            )
            Text(
                text = "Ledger",
                fontSize = 36.sp,
                fontWeight = FontWeight.Light,
                color = Color(0xFF64B5F6),
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Daily Expense Tracker",
                fontSize = 14.sp,
                color = Color(0xAAFFFFFF),
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(64.dp))

            // Google Sign-In button
            Button(
                onClick = onSignInClick,
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF1F1F1F),
                    disabledContainerColor = Color(0x99FFFFFF)
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 2.dp,
                    pressedElevation = 0.dp
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF1F1F1F)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                // Google "G" as text (avoids needing a drawable resource)
                Text(
                    text = "G",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF4285F4)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (isLoading) "Signing in…" else "Sign in with Google",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Error message
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(20.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0x33FF5252)
                    )
                ) {
                    Text(
                        text = errorMessage,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        color = Color(0xFFFF8A80),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
