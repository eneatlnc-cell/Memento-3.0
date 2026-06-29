package com.myagent.app.ui

import com.myagent.app.MainViewModel
import com.myagent.app.model.DownloadForegroundService
import com.myagent.app.model.ModelDownloadState
import com.myagent.app.model.PersonaType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 引导流程 — 模型下载（强制） → 人格选择。
 *
 * 注意：欢迎页已移至 RootScreen 作为第一步。
 * 模型下载强制完成，退出下载页时启动 ForegroundService 后台下载。
 *
 * @param onComplete 引导完成后回调，由 NavHost 触发导航到主界面。
 */
@Composable
fun OnboardingFlow(
  viewModel: MainViewModel,
  modifier: Modifier = Modifier,
  onComplete: () -> Unit = {},
) {
  var step by rememberSaveable { mutableIntStateOf(0) }
  val downloadState by viewModel.downloadState.collectAsState()
  val context = LocalContext.current

  // 进入下载页时自动触发下载
  LaunchedEffect(Unit) {
    viewModel.startModelDownload()
  }

  // 下载完成后自动跳到人格选择；未完成则强制回到下载页
  LaunchedEffect(downloadState, step) {
    when {
      downloadState is ModelDownloadState.Completed && step == 0 -> step = 1
      downloadState !is ModelDownloadState.Completed && step == 1 -> step = 0
    }
  }

  Surface(modifier = modifier) {
    when (step) {
      0 -> ModelDownloadStep(
        state = downloadState,
        retryCount = viewModel.downloadRetryCount.value,
        onExit = {
          DownloadForegroundService.start(context)
        },
        onRetry = {
          viewModel.resetModelDownload()
        },
      )
      1 -> PersonaStep(
        onSelect = { persona ->
          viewModel.lockPersona(persona)
          viewModel.setOnboardingCompleted(true)
          onComplete()
        },
      )
    }
  }
}

@Composable
private fun ModelDownloadStep(
  state: ModelDownloadState,
  retryCount: Int,
  onExit: () -> Unit,
  onRetry: () -> Unit,
) {
  ModelDownloadScreen(
    state = state,
    onExit = onExit,
    onRetry = onRetry,
    retryCount = retryCount,
  )
}

@Composable
private fun PersonaStep(onSelect: (PersonaType) -> Unit) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(
      text = "选择 AI 人格",
      fontSize = 26.sp,
      fontWeight = FontWeight.Bold,
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
      text = "选一个你喜欢的搭子风格",
      fontSize = 14.sp,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(28.dp))

    val personaCards = listOf(
      PersonaCardData(
        persona = PersonaType.FUNNY,
        icon = Icons.Default.Mood,
        title = "逗比型",
        desc = "幽默、玩梗、轻松活泼",
        emoji = "😄",
        color = Color(0xFF6C3CE0),
      ),
      PersonaCardData(
        persona = PersonaType.WARM,
        icon = Icons.Default.Favorite,
        title = "温柔型",
        desc = "暖心细腻，善于倾听",
        emoji = "🌸",
        color = Color(0xFFFFA94D),
      ),
      PersonaCardData(
        persona = PersonaType.SHARP,
        icon = Icons.Default.AutoAwesome,
        title = "毒舌型",
        desc = "犀利精准，一针见血",
        emoji = "⚡",
        color = Color(0xFF00d4aa),
      ),
      PersonaCardData(
        persona = PersonaType.SCHOLAR,
        icon = Icons.Default.School,
        title = "学霸型",
        desc = "严谨、逻辑、深度思考",
        emoji = "📖",
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
      Icon(
        imageVector = data.icon,
        contentDescription = null,
        tint = data.color,
        modifier = Modifier.size(36.dp),
      )
      Spacer(modifier = Modifier.width(14.dp))
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
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = "选择",
          fontSize = 13.sp,
          color = data.color.copy(alpha = 0.6f),
          fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
          text = "›",
          fontSize = 24.sp,
          color = data.color.copy(alpha = 0.5f),
          fontWeight = FontWeight.Light,
        )
      }
    }
  }
}