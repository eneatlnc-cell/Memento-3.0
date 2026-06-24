package com.myagent.app.ui

import com.myagent.app.MainViewModel
import com.myagent.app.model.PersonaType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 首次使用引导流程 — 欢迎页 + 人格选择。
 */
@Composable
fun OnboardingFlow(
  viewModel: MainViewModel,
  modifier: Modifier = Modifier,
) {
  var step by rememberSaveable { mutableIntStateOf(0) }

  Surface(modifier = modifier) {
    when (step) {
      0 -> WelcomeStep(onNext = { step = 1 })
      1 -> PersonaStep(
        onSelect = { persona ->
          viewModel.setPersona(persona)
          viewModel.setOnboardingCompleted(true)
        },
      )
    }
  }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Icon(
      imageVector = Icons.Default.Chat,
      contentDescription = null,
      modifier = Modifier.size(80.dp),
      tint = MaterialTheme.colorScheme.primary,
    )
    Spacer(modifier = Modifier.height(24.dp))
    Text(
      text = "欢迎来到灵机",
      fontSize = 28.sp,
      fontWeight = FontWeight.Bold,
      textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = "你的 AI 搭子，永远在线",
      fontSize = 16.sp,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(48.dp))
    Button(
      onClick = onNext,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text("开始使用")
    }
  }
}

@Composable
private fun PersonaStep(onSelect: (PersonaType) -> Unit) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(
      text = "选择 AI 人格",
      fontSize = 24.sp,
      fontWeight = FontWeight.Bold,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = "选一个你喜欢的搭子风格（随时可在设置中切换）",
      fontSize = 14.sp,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(32.dp))

    val personas = listOf(
      PersonaType.FUNNY to "😎 逗比型" to "幽默风趣，会用网络热梗",
      PersonaType.WARM to "❤️ 暖心型" to "温柔贴心，像知心朋友",
      PersonaType.COOL to "😏 高冷型" to "话少但精，偶尔毒舌",
      PersonaType.SCHOLAR to "📚 学霸型" to "博学多才，通俗易懂",
    )

    for ((persona, desc) in personas) {
      Button(
        onClick = { onSelect(persona.first) },
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 4.dp),
      ) {
        Column(
          modifier = Modifier.padding(vertical = 8.dp),
          horizontalAlignment = Alignment.Start,
        ) {
          Text(
            text = persona.second,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
          )
          Text(
            text = desc,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}