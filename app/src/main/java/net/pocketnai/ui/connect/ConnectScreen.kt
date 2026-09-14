package net.pocketnai.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import android.content.Intent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import net.pocketnai.R
import net.pocketnai.data.security.CredentialType
import net.pocketnai.ui.LocalAppContainer
import net.pocketnai.ui.common.messageRes

/**
 * 连接页：两种方式并存。
 *
 * 默认选中 Persistent Token，因为它是官方当前推荐的第三方接入方式；
 * 账号登录标为"实验"，并且在旁边直接给出失败后的退路。
 *
 * 密码的处理是这一页最敏感的 part：
 * - 默认遮挡，可短暂显示；
 * - 应用进入后台（`ON_PAUSE`）与离开页面（`onDispose`）都会清空密码输入；
 * - 不放进导航参数、不写 `SavedStateHandle`、不落任何偏好设置。
 *
 * 屏幕旋转会触发一次暂停，因此密码也会被清空 —— 这是有意的选择：安全优先，
 * 让用户重输一次，而不是把密码留在状态里跨配置变更存活。
 */
@Composable
fun ConnectScreen(onConnected: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel: ConnectViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                ConnectViewModel(
                    credentialStore = container.credentialStore,
                    api = container.api,
                    authApi = container.authApi,
                    accessKeyDeriver = container.accessKeyDeriver,
                    sessionState = container.sessionState,
                )
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) viewModel.clearSensitiveInput()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.clearSensitiveInput()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.connect_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.connect_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.connected) {
            ConnectedCard(
                type = state.credentialType,
                loginMethod = state.account?.loginMethod,
                hintLength = state.hint?.length,
                fingerprint = state.hint?.fingerprint,
                onReplace = viewModel::disconnect,
                onDelete = { showDeleteConfirm = true },
            )
        } else {
            ModeSelector(
                mode = state.mode,
                onModeChange = viewModel::onModeChange,
            )

            when (state.mode) {
                AuthMode.PERSISTENT_TOKEN -> PersistentTokenForm(
                    state = state,
                    viewModel = viewModel,
                )

                AuthMode.ACCOUNT_LOGIN -> AccountLoginForm(
                    state = state,
                    viewModel = viewModel,
                )
            }
        }

        state.error?.let { error ->
            ErrorCard(
                message = stringResource(error.code.messageRes()),
                detail = error.detail,
                onDismiss = viewModel::dismissError,
            )
        }

        state.notice?.let { notice ->
            NoticeCard(
                message = when (notice) {
                    ConnectNotice.SESSION_OBTAINED_BUT_UNVERIFIED ->
                        stringResource(R.string.connect_notice_session_unverified)
                },
                onDismiss = viewModel::dismissNotice,
            )
        }

        if (state.connected) {
            Button(onClick = onConnected, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.nav_generate))
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.connect_delete_confirm_title)) },
            text = { Text(stringResource(R.string.connect_delete_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.disconnect()
                    },
                ) {
                    Text(stringResource(R.string.connect_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ModeSelector(mode: AuthMode, onModeChange: (AuthMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = mode == AuthMode.PERSISTENT_TOKEN,
            onClick = { onModeChange(AuthMode.PERSISTENT_TOKEN) },
            label = { Text(stringResource(R.string.connect_mode_persistent)) },
        )
        FilterChip(
            selected = mode == AuthMode.ACCOUNT_LOGIN,
            onClick = { onModeChange(AuthMode.ACCOUNT_LOGIN) },
            label = { Text(stringResource(R.string.connect_mode_account)) },
        )
    }
}

@Composable
private fun PersistentTokenForm(
    state: ConnectViewModel.UiState,
    viewModel: ConnectViewModel,
) {
    Text(
        text = stringResource(R.string.connect_how_to),
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = state.tokenInput,
        onValueChange = viewModel::onTokenInputChange,
        label = { Text(stringResource(R.string.connect_token_label)) },
        singleLine = true,
        // Token 属于密码级凭据：输入时遮挡，且不做任何自动补全。
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        HintLine(stringResource(R.string.connect_pst_stable))
        HintLine(stringResource(R.string.connect_pst_where))
        HintLine(stringResource(R.string.connect_pst_replace_warning))
    }
    // 只打开官方网页，不抓 Cookie、不代取 Token（计划书 §7.2）。
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, "https://novelai.net/".toUri()),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.connect_open_novelai))
    }
    HintLine(stringResource(R.string.connect_open_novelai_hint))
    Button(
        onClick = viewModel::connectWithPersistentToken,
        enabled = state.canSubmitToken,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.submitting) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Text(
                text = stringResource(R.string.connect_verifying),
                modifier = Modifier.padding(start = 8.dp),
            )
        } else {
            Text(stringResource(R.string.connect_action))
        }
    }
}

@Composable
private fun AccountLoginForm(
    state: ConnectViewModel.UiState,
    viewModel: ConnectViewModel,
) {
    // 这一步比"登录失败后再解释"更有用：先告诉用户哪类账号不该走这条路。
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.connect_account_sso_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }

    OutlinedTextField(
        value = state.emailInput,
        onValueChange = viewModel::onEmailInputChange,
        label = { Text(stringResource(R.string.connect_account_email)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.passwordInput,
        onValueChange = viewModel::onPasswordInputChange,
        label = { Text(stringResource(R.string.connect_account_password)) },
        singleLine = true,
        visualTransformation = if (state.passwordVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        trailingIcon = {
            IconButton(onClick = viewModel::onTogglePasswordVisibility) {
                Icon(
                    imageVector = if (state.passwordVisible) {
                        Icons.Default.VisibilityOff
                    } else {
                        Icons.Default.Visibility
                    },
                    contentDescription = stringResource(
                        if (state.passwordVisible) {
                            R.string.connect_hide_password
                        } else {
                            R.string.connect_show_password
                        },
                    ),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )

    Button(
        onClick = viewModel::loginWithAccount,
        enabled = state.canSubmitLogin,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.submitting) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Text(
                text = stringResource(R.string.connect_account_logging_in),
                modifier = Modifier.padding(start = 8.dp),
            )
        } else {
            Text(stringResource(R.string.connect_account_login))
        }
    }

    TextButton(
        onClick = { viewModel.onModeChange(AuthMode.PERSISTENT_TOKEN) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.connect_account_fallback))
    }

    // 实验性质与凭据处理方式必须写清楚，不能让人以为这是官方支持的登录方式。
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.connect_account_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun HintLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ConnectedCard(
    type: CredentialType?,
    loginMethod: String?,
    hintLength: Int?,
    fingerprint: String?,
    onReplace: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(
                        when (type) {
                            CredentialType.PERSISTENT_API_TOKEN ->
                                R.string.connect_credential_persistent

                            CredentialType.ACCOUNT_SESSION ->
                                R.string.connect_credential_session

                            null -> R.string.connect_credential_unknown
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            // 只显示长度与不可逆指纹，不显示 Token 的任何片段，也不显示邮箱。
            val fallback = stringResource(R.string.connect_token_hint_never_shown)
            val hintText = buildString {
                if (fingerprint != null && hintLength != null) {
                    append("长度 $hintLength · 指纹 $fingerprint")
                }
            }.ifEmpty { fallback }
            Text(
                text = hintText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )

            if (loginMethod == "sso") {
                Text(
                    text = stringResource(R.string.connect_login_method_sso),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            if (type == CredentialType.ACCOUNT_SESSION) {
                // 不承诺精确到期时间：有效期以服务端返回的 401 为准。
                Text(
                    text = stringResource(R.string.connect_session_may_expire),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReplace) {
                    Text(stringResource(R.string.connect_replace))
                }
                OutlinedButton(onClick = onDelete) {
                    Text(stringResource(R.string.connect_delete))
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, detail: String?, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            // detail 只包含状态码这类非敏感信息，凭据相关内容从不写进这里。
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_confirm))
            }
        }
    }
}

@Composable
private fun NoticeCard(message: String, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_confirm))
            }
        }
    }
}
