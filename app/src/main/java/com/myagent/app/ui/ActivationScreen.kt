package com.myagent.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 激活页面 — 输入激活码解锁应用。
 *
 * 深色背景 + 居中卡片式布局，简洁克制。
 * 错误提示淡入淡出，不打断用户输入。
 */
@Composable
fun ActivationScreen(
  onActivate: (code: String) -> Boolean,
  modifier: Modifier = Modifier,
) {
  var code by remember { mutableStateOf("") }
  var errorMessage by remember { mutableStateOf<String?>(null) }
  var showError by remember { mutableStateOf(false) }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color(0xFF0A0A0A)),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 40.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      // 品牌标识
      Text(
        text = "Memento",
        color = Color.White,
        fontSize = 36.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = "MEMENTO",
        color = Color.White.copy(alpha = 0.4f),
        fontSize = 14.sp,
        fontWeight = FontWeight.Light,
        letterSpacing = 4.sp,
        textAlign = TextAlign.Center,
      )
      Spacer(modifier = Modifier.height(40.dp))

      // 激活码输入
      OutlinedTextField(
        value = code,
        onValueChange = {
          code = it
          if (showError) {
            showError = false
            errorMessage = null
          }
        },
        label = { Text("激活码", color = Color.White.copy(alpha = 0.6f)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
          focusedTextColor = Color.White,
          unfocusedTextColor = Color.White,
          cursorColor = Color.White,
          focusedBorderColor = Color.White.copy(alpha = 0.6f),
          unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
          focusedLabelColor = Color.White.copy(alpha = 0.8f),
          unfocusedLabelColor = Color.White.copy(alpha = 0.4f),
        ),
        keyboardOptions = KeyboardOptions(
          keyboardType = KeyboardType.Ascii,
          imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
          onDone = {
            if (code.isNotBlank()) {
              val success = onActivate(code.trim())
              if (!success) {
                errorMessage = "激活码无效，请检查后重试"
                showError = true
              }
            }
          }
        ),
        shape = RoundedCornerShape(12.dp),
      )

      Spacer(modifier = Modifier.height(8.dp))

      // 错误提示（淡入淡出）
      AnimatedVisibility(
        visible = showError && errorMessage != null,
        enter = fadeIn(),
        exit = fadeOut(),
      ) {
        Text(
          text = errorMessage ?: "",
          color = Color(0xFFFF6B6B),
          fontSize = 13.sp,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth(),
        )
      }

      Spacer(modifier = Modifier.height(24.dp))

      // 激活按钮
      Button(
        onClick = {
          if (code.isBlank()) {
            errorMessage = "请输入激活码"
            showError = true
          } else {
            val success = onActivate(code.trim())
            if (!success) {
              errorMessage = "激活码无效，请检查后重试"
              showError = true
            }
          }
        },
        modifier = Modifier
          .fillMaxWidth()
          .height(50.dp),
        colors = ButtonDefaults.buttonColors(
          containerColor = Color.White,
          contentColor = Color.Black,
        ),
        shape = RoundedCornerShape(12.dp),
        enabled = code.isNotBlank(),
      ) {
        Text(
          text = "激活",
          fontSize = 16.sp,
          fontWeight = FontWeight.SemiBold,
        )
      }

      Spacer(modifier = Modifier.height(32.dp))

      Text(
        text = "请输入有效的激活码以解锁 Memento",
        color = Color.White.copy(alpha = 0.3f),
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
      )
    }
  }
}