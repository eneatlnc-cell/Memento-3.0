package com.myagent.app.ui

import com.myagent.app.GatewaySkillSummary
import com.myagent.app.MainViewModel
import com.myagent.app.ui.design.ClawDetailRow
import com.myagent.app.ui.design.ClawListPanel
import com.myagent.app.ui.design.ClawPanel
import com.myagent.app.ui.design.ClawPrimaryButton
import com.myagent.app.ui.design.ClawSecondaryButton
import com.myagent.app.ui.design.ClawStatus
import com.myagent.app.ui.design.ClawStatusPill
import com.myagent.app.ui.design.ClawTextBadge
import com.myagent.app.ui.design.ClawTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Skills screen with tab switcher: Installed / Marketplace browse. */
@Composable
internal fun SkillsSettingsScreen(
  viewModel: MainViewModel,
  onBack: () -> Unit,
) {
  val skillsSummary by viewModel.skillsSummary.collectAsState()
  val skillsRefreshing by viewModel.skillsRefreshing.collectAsState()
  val skillsErrorText by viewModel.skillsErrorText.collectAsState()
  val isConnected by viewModel.isConnected.collectAsState()
  val skills = skillsSummary.skills
  val readyCount = skills.count { skillReady(it) }
  val needsSetupCount = skills.count { skillNeedsSetup(it) }

  var activeTab by remember { mutableStateOf("installed") }

  LaunchedEffect(isConnected) {
    if (isConnected) {
      viewModel.refreshSkills()
    }
  }

  SettingsDetailFrame(
    title = "Skills",
    subtitle = if (activeTab == "installed") "已安装的技能列表" else "浏览技能市场",
    icon = Icons.Default.Settings,
    onBack = onBack,
  ) {
    // Tab switcher
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(ClawTheme.radii.control))
        .background(ClawTheme.colors.surfacePressed),
      horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
      SkillsTab(
        text = "已安装",
        selected = activeTab == "installed",
        onClick = { activeTab = "installed" },
        modifier = Modifier.weight(1f),
      )
      SkillsTab(
        text = "市场浏览",
        selected = activeTab == "marketplace",
        onClick = { activeTab = "marketplace" },
        modifier = Modifier.weight(1f),
      )
    }

    when (activeTab) {
      "installed" -> {
        SettingsMetricPanel(
          rows =
            listOf(
              SettingsMetric("Installed", skills.size.toString()),
              SettingsMetric("Ready", readyCount.toString()),
              SettingsMetric("Needs Setup", needsSetupCount.toString()),
            ),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ClawSecondaryButton(
            text = if (skillsRefreshing) "Refreshing" else "Refresh",
            onClick = viewModel::refreshSkills,
            enabled = isConnected && !skillsRefreshing,
            modifier = Modifier.weight(1f),
          )
        }
        skillsErrorText?.let { errorText ->
          ClawPanel {
            Text(text = errorText, style = ClawTheme.type.body, color = ClawTheme.colors.warning)
          }
        }
        when {
          !isConnected ->
            ClawPanel {
              Text(text = "Connect the gateway to load skills.", style = ClawTheme.type.body, color = ClawTheme.colors.textMuted)
            }
          skills.isEmpty() ->
            ClawPanel {
              Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(text = "No skills installed.", style = ClawTheme.type.section, color = ClawTheme.colors.text)
                Text(text = "Skills installed on the gateway will appear here.", style = ClawTheme.type.body, color = ClawTheme.colors.textMuted)
              }
            }
          else -> SkillsPanel(skills = skills)
        }
      }
      "marketplace" -> {
        // Marketplace preview — simplifed browse view
        MarketplacePreviewSection()
      }
    }
  }
}

@Composable
private fun SkillsTab(
  text: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val bgColor = if (selected) ClawTheme.colors.primary else ClawTheme.colors.surfacePressed
  val textColor = if (selected) ClawTheme.colors.primaryText else ClawTheme.colors.textMuted

  Text(
    text = text,
    style = ClawTheme.type.body.copy(
      fontSize = 13.sp,
      fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
    ),
    color = textColor,
    modifier = modifier
      .clip(RoundedCornerShape(ClawTheme.radii.control))
      .background(bgColor)
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 10.dp),
    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
  )
}

@Composable
private fun MarketplacePreviewSection() {
  val placeholderSkills = listOf(
    Triple("AI 写作助手", "帮助撰写各类文档、邮件和创意内容", "📝"),
    Triple("代码审查", "自动审查代码质量，发现潜在 Bug", "🔍"),
    Triple("日程管理", "智能管理日程、提醒和待办事项", "📅"),
    Triple("翻译助手", "多语言实时翻译，支持 50+ 语言", "🌐"),
    Triple("笔记整理", "自动整理笔记，生成结构化摘要", "📋"),
    Triple("数据分析", "快速分析数据，生成可视化报告", "📊"),
  )

  ClawPanel {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
        text = "灵机技能市场",
        style = ClawTheme.type.section,
        color = ClawTheme.colors.text,
      )
      Text(
        text = "浏览并安装来自社区的 AI 技能。接入灵机网站 API 后将展示完整技能列表。",
        style = ClawTheme.type.body,
        color = ClawTheme.colors.textMuted,
      )
    }
  }

  ClawListPanel(items = placeholderSkills) { (name, desc, emoji) ->
    ClawDetailRow(
      title = name,
      subtitle = desc,
      leading = { ClawTextBadge(text = emoji) },
      trailing = {
        ClawPrimaryButton(
          text = "安装",
          onClick = { /* TODO: 接入 API 后实现 */ },
        )
      },
    )
  }
}

@Composable
private fun SkillsPanel(skills: List<GatewaySkillSummary>) {
  ClawListPanel(items = skills) { skill ->
    SkillListRow(skill = skill)
  }
}

@Composable
private fun SkillListRow(skill: GatewaySkillSummary) {
  ClawDetailRow(
    title = skill.name,
    subtitle = skillSubtitle(skill),
    leading = { ClawTextBadge(text = skillBadge(skill)) },
    trailing = { ClawStatusPill(text = skillStatusText(skill), status = skillStatus(skill)) },
  )
}

private fun skillReady(skill: GatewaySkillSummary): Boolean = !skill.disabled && skill.eligible && skill.missingCount == 0

private fun skillNeedsSetup(skill: GatewaySkillSummary): Boolean = !skill.disabled && (skill.blockedByAllowlist || !skill.eligible || skill.missingCount > 0)

private fun skillStatusText(skill: GatewaySkillSummary): String =
  when {
    skill.disabled -> "Off"
    skillNeedsSetup(skill) -> "Setup"
    else -> "Ready"
  }

private fun skillStatus(skill: GatewaySkillSummary): ClawStatus =
  when {
    skill.disabled -> ClawStatus.Neutral
    skillNeedsSetup(skill) -> ClawStatus.Warning
    else -> ClawStatus.Success
  }

private fun skillSubtitle(skill: GatewaySkillSummary): String {
  val issue =
    when {
      skill.disabled -> "Disabled"
      skill.blockedByAllowlist -> "Blocked"
      skill.missingCount > 0 -> "${skill.missingCount} missing"
      !skill.eligible -> "Needs setup"
      else -> null
    }
  return listOfNotNull(skill.description, skillSourceLabel(skill), issue).joinToString(" · ")
}

private fun skillSourceLabel(skill: GatewaySkillSummary): String =
  when (skill.source) {
    "openclaw-bundled" -> if (skill.bundled) "Built-in" else "Bundled"
    "openclaw-managed" -> "Installed"
    "openclaw-workspace" -> "Workspace"
    "openclaw-extra" -> "Extra"
    else -> "Skill"
  }

private fun skillBadge(skill: GatewaySkillSummary): String {
  skill.emoji?.let { return it }
  return skill.name
    .split(' ', '-', '_')
    .filter { it.isNotBlank() }
    .take(2)
    .mapNotNull { it.firstOrNull()?.uppercaseChar()?.toString() }
    .joinToString("")
    .ifBlank { "S" }
}