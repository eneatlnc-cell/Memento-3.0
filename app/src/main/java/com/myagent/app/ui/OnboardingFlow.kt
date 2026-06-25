package com.myagent.app.ui

import com.myagent.app.MainViewModel
import com.myagent.app.model.ModelDownloadState
import com.myagent.app.model.PersonaType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 首次使用引导流程 — 欢迎页 → 模型下载 → 人格选择。
 */
@Composable
fun OnboardingFlow(
  viewModel: MainViewModel,
  modifier: Modifier = Modifier,
) {
  var step by rememberSaveable { mutableIntStateOf(0) }
  val downloadState by viewModel.downloadState.collectAsState()

  // 自动前进：下载完成后跳到人格选择
  LaunchedEffect(downloadState) {
    if (downloadState is ModelDownloadState.Completed && step == 1) {
      step = 2
    }
  }

  Surface(modifier = modifier) {
    when (step) {
      0 -> WelcomeStep(onNext = {
        viewModel.startModelDownload()
        step = 1
      })
      1 -> ModelDownloadStep(
        state = downloadState,
        onSkip = {
          viewModel.skipModelDownload()
          step = 2
        },
        onRetry = {
          viewModel.startModelDownload()
        },
      )
      2 -> PersonaStep(
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
      imageVector = Icons.Default.AutoAwesome,
      contentDescription = null,
      modifier = Modifier.size(80.dp),
      tint = Color(0xFF6C5CE7),
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
private fun ModelDownloadStep(
  state: ModelDownloadState,
  onSkip: () -> Unit,
  onRetry: () -> Unit,
) {
  ModelDownloadScreen(
    state = state,
    onSkip = onSkip,
    onRetry = onRetry,
  )
}

@Composable
private fun PersonaStep(onSelect: (PersonaType) -> Unit) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(
      text = "选择 AI 人格",
      fontSize = 26.sp,
      fontWeight = FontWeight.Bold,
      textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
      text = "选一个你喜欢的搭子风格",
      fontSize = 14.sp,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(28.dp))

    val personaCards = listOf(
      PersonaCardData(
        persona = PersonaType.FUNNY,
        icon = Icons.Default.Mood,
        title = "逗比型",
        desc = "幽默风趣，会用网络热梗",
        emoji = "😎",
        color = Color(0xFFFF6B6B),
      ),
      PersonaCardData(
        persona = PersonaType.WARM,
        icon = Icons.Default.Favorite,
        title = "暖心型",
        desc = "温柔贴心，像知心朋友",
        emoji = "❤️",
        color = Color(0xFFFFA94D),
      ),
      PersonaCardData(
        persona = PersonaType.COOL,
        icon = Icons.Default.AutoAwesome,
        title = "高冷型",
        desc = "话少但精，偶尔毒舌",
        emoji = "😏",
        color = Color(0xFF6C5CE7),
      ),
      PersonaCardData(
        persona = PersonaType.SCHOLAR,
        icon = Icons.Default.School,
        title = "学霸型",
        desc = "博学多才，通俗易懂",
        emoji = "📚",
        color = Color(0xFF4ECDC4),
      ),
    )

    for (data in personaCards) {
      PersonaCard(
        data = data,
        onClick = { onSelect(data.persona) },
      )
      Spacer(modifier = Modifier.height(10.dp))
    }
  }
}

private data class PersonaCardData(
  val persona: PersonaType,
  val icon: ImageVector,
  val title: String,
  val desc: String,
  val emoji: String,
  val color: Color,
)

@Composable
private fun PersonaCard(
  data: PersonaCardData,
  onClick: () -> Unit,
) {
  Card(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(
      containerColor = data.color.copy(alpha = 0.08f),
    ),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // 左侧彩色图标
      Icon(
        imageVector = data.icon,
        contentDescription = null,
        tint = data.color,
        modifier = Modifier.size(36.dp),
      )
      Spacer(modifier = Modifier.width(14.dp))
      // 中间文字
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "${data.emoji} ${data.title}",
          fontWeight = FontWeight.SemiBold,
          fontSize = 16.sp,
          color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
          text = data.desc,
          fontSize = 13.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      // 右侧箭头
      Text(
        text = "›",
        fontSize = 24.sp,
        color = data.color.copy(alpha = 0.5f),
        fontWeight = FontWeight.Light,
      )
    }
  }
}