package com.tongxie.copilotgo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tongxie.copilotgo.data.tools.CredentialUpdate
import com.tongxie.copilotgo.data.tools.McpAuthMode
import com.tongxie.copilotgo.data.tools.McpServerDraft
import com.tongxie.copilotgo.data.tools.McpServerSettings
import com.tongxie.copilotgo.data.tools.SearchProvider
import com.tongxie.copilotgo.data.tools.ToolException
import com.tongxie.copilotgo.data.tools.ToolProblem
import com.tongxie.copilotgo.data.tools.ToolProblemCode
import com.tongxie.copilotgo.data.tools.ToolSettingsLimits
import com.tongxie.copilotgo.data.tools.ToolSettingsSnapshot
import com.tongxie.copilotgo.data.tools.ToolSettingsStore
import com.tongxie.copilotgo.data.tools.WebToolSettingsDraft
import com.tongxie.copilotgo.data.tools.mcp.RemoteMcpService
import com.tongxie.copilotgo.ui.settings.ToolCredentialAction
import com.tongxie.copilotgo.ui.settings.ToolMcpForm
import com.tongxie.copilotgo.ui.settings.ToolSearchForm
import com.tongxie.copilotgo.ui.settings.canBeSelected
import com.tongxie.copilotgo.ui.settings.currentToolDiscovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ToolSettingsPending { RELOAD, SAVE_SEARCH, SAVE_SERVER, DELETE_SERVER, SET_ENABLED }

data class ToolSettingsEditorState(
    val search: ToolSearchForm? = null,
    val server: ToolMcpForm? = null,
    val pending: ToolSettingsPending? = null,
    val problem: ToolProblem? = null,
    val savedNotice: Boolean = false,
    val saveSequence: Long = 0,
    val deleted: Boolean = false,
    val checking: Boolean = false,
    val discoveryCancelled: Boolean = false
)

/** Route-scoped, nonsecret drafts only; the services remain the configuration/discovery owners. */
class ToolSettingsViewModel(
    private val store: ToolSettingsStore,
    private val remoteMcp: RemoteMcpService
) : ViewModel() {
    val settings = store.state
    val discovery = remoteMcp.discovery
    private val _state = MutableStateFlow(ToolSettingsEditorState())
    val state = _state.asStateFlow()
    private var editor: Editor? = null
    private var discoveryJob: Job? = null
    private var discoveryGeneration = 0L

    init {
        viewModelScope.launch {
            settings.collect { configuration ->
                if (!configuration.loading && configuration.problem == null) {
                    val form = _state.value.server
                    if (_state.value.checking && form != null &&
                        form.isStale(configuration.snapshot?.servers?.firstOrNull { it.id == form.serverId })
                    ) {
                        cancelDiscovery()
                        configurationChanged()
                    }
                    receiveConfiguration()
                }
            }
        }
    }

    fun openSearch() {
        if (editor == Editor.Search) {
            receiveConfiguration()
            return
        }
        if (!available()) return
        cancelDiscovery()
        editor = Editor.Search
        _state.value = ToolSettingsEditorState()
        receiveConfiguration()
    }

    fun openServer(serverId: String?) {
        val target = Editor.Server(serverId)
        if ((editor as? Editor.Server)?.routeId == serverId && editor is Editor.Server) {
            receiveConfiguration()
            return
        }
        if (!available()) return
        cancelDiscovery()
        editor = target
        _state.value = ToolSettingsEditorState()
        if (serverId != null && !SERVER_ID.matches(serverId)) {
            fail(ToolProblemCode.CONFIGURATION_CHANGED, "此 MCP 服务标识无效，请返回工具列表。")
            return
        }
        receiveConfiguration()
    }

    fun editSearch(transform: (WebToolSettingsDraft) -> WebToolSettingsDraft) {
        if (!available()) return
        val form = _state.value.search ?: return notReady()
        val updated = form.copy(draft = transform(form.draft))
        if (updated != form) _state.value = _state.value.copy(search = updated, problem = null, savedNotice = false)
    }

    fun setSearchCredentialAction(action: ToolCredentialAction) {
        if (!available()) return
        val form = _state.value.search ?: return notReady()
        _state.value = _state.value.copy(
            search = form.copy(credentialAction = action), problem = null, savedNotice = false
        )
    }

    fun editServer(transform: (McpServerDraft) -> McpServerDraft) {
        if (!available()) return
        val form = _state.value.server ?: return notReady()
        val updated = form.edit(transform(form.draft))
        if (updated != form) {
            cancelDiscovery()
            _state.value = _state.value.copy(server = updated, problem = null, savedNotice = false)
        }
    }

    fun setServerCredentialAction(action: ToolCredentialAction) {
        if (!available()) return
        val form = _state.value.server ?: return notReady()
        cancelDiscovery()
        _state.value = _state.value.copy(
            server = form.copy(credentialAction = action), problem = null, savedNotice = false
        )
    }

    fun saveSearch(credential: CredentialUpdate = CredentialUpdate.Keep) {
        if (!available()) return
        val form = _state.value.search ?: return notReady()
        val current = readySnapshot()?.web ?: return
        if (form.isStale(current)) return configurationChanged()
        if (!form.dirty) return fail(ToolProblemCode.INVALID_CONFIGURATION, "没有尚未保存的修改。")
        if (!credentialMatches(form.credentialAction, credential) ||
            (credential is CredentialUpdate.Replace && form.draft.provider != SearchProvider.EXA_API_KEY)
        ) return fail(ToolProblemCode.INVALID_CONFIGURATION, "请选择自备密钥方式，并明确选择写入、保留或删除凭据。")
        launchMutation(ToolSettingsPending.SAVE_SEARCH) {
            val saved = store.updateWeb(form.draft, form.original.revision, credential)
            _state.value = _state.value.copy(
                search = ToolSearchForm(saved), savedNotice = true, problem = null,
                saveSequence = _state.value.saveSequence + 1
            )
        }
    }

    fun saveServer(credential: CredentialUpdate = CredentialUpdate.Keep) {
        if (!available()) return
        val form = _state.value.server ?: return notReady()
        val snapshot = readySnapshot() ?: return
        val current = snapshot.servers.firstOrNull { it.id == form.serverId }
        if (form.isStale(current)) return configurationChanged()
        if (!form.validation.valid) return fail(
            ToolProblemCode.INVALID_CONFIGURATION, "请修正名称、HTTPS 地址、认证请求头或工具选择；原始输入已保留。"
        )
        if (!form.dirty) return fail(ToolProblemCode.INVALID_CONFIGURATION, "没有尚未保存的修改。")
        if (form.needsCredentialDecision) return fail(
            ToolProblemCode.INVALID_CONFIGURATION, "地址或认证方式已改变，请明确替换或删除旧凭据。"
        )
        if (!credentialMatches(form.credentialAction, credential) ||
            (credential is CredentialUpdate.Replace && form.draft.authMode == McpAuthMode.NONE)
        ) return fail(ToolProblemCode.INVALID_CONFIGURATION, "请明确选择写入、保留或删除此服务的凭据。")
        if (!validateNewToolSelections(form, current)) return
        cancelDiscovery()
        launchMutation(ToolSettingsPending.SAVE_SERVER) {
            val saved = store.saveServer(form.serverId, form.expectedRevision, form.draft, credential)
            editor = (editor as? Editor.Server)?.copy(id = saved.id) ?: Editor.Server(saved.id)
            _state.value = _state.value.copy(
                server = ToolMcpForm(saved.id, saved), savedNotice = true, problem = null,
                saveSequence = _state.value.saveSequence + 1, discoveryCancelled = false
            )
        }
    }

    fun setServerEnabled(serverId: String, expectedRevision: Long, enabled: Boolean) {
        if (!available()) return
        val snapshot = readySnapshot() ?: return
        val current = snapshot.servers.firstOrNull { it.id == serverId }
            ?: return fail(ToolProblemCode.CONFIGURATION_CHANGED, "此 MCP 服务已不存在，请重新读取工具列表。")
        if (current.revision != expectedRevision) return configurationChanged()
        cancelDiscovery()
        launchMutation(ToolSettingsPending.SET_ENABLED) {
            store.saveServer(serverId, expectedRevision, McpServerDraft(current).copy(enabled = enabled), CredentialUpdate.Keep)
        }
    }

    fun deleteServer() {
        if (!available()) return
        val form = _state.value.server ?: return notReady()
        val serverId = form.serverId ?: return fail(ToolProblemCode.INVALID_CONFIGURATION, "此服务尚未保存，无需删除。")
        val snapshot = readySnapshot() ?: return
        val current = snapshot.servers.firstOrNull { it.id == serverId } ?: return configurationChanged()
        if (form.isStale(current)) return configurationChanged()
        cancelDiscovery()
        launchMutation(ToolSettingsPending.DELETE_SERVER) {
            store.deleteServer(serverId, current.revision)
            _state.value = _state.value.copy(server = null, deleted = true, problem = null)
        }
    }

    fun consumeDeleted() {
        _state.value = _state.value.copy(deleted = false)
    }

    fun reload() {
        if (!available()) return
        launchMutation(ToolSettingsPending.RELOAD) {
            store.reload()
            store.awaitReady()
            receiveConfiguration(force = true)
        }
    }

    fun resetEditor() {
        if (!available()) return
        cancelDiscovery()
        _state.value = _state.value.copy(
            search = null, server = null, problem = null, savedNotice = false, discoveryCancelled = false
        )
        reload()
    }

    fun discardEditor() {
        if (!available()) return
        cancelDiscovery()
        _state.value = _state.value.copy(
            search = null, server = null, problem = null, savedNotice = false, discoveryCancelled = false
        )
        receiveConfiguration()
    }

    fun discover() {
        if (!available()) return
        val form = _state.value.server ?: return notReady()
        val snapshot = readySnapshot() ?: return
        val saved = snapshot.servers.firstOrNull { it.id == form.serverId } ?: return fail(
            ToolProblemCode.INVALID_CONFIGURATION, "请先保存远程服务，再检查已保存的确切配置版本。"
        )
        if (form.isStale(saved)) return configurationChanged()
        if (!form.canSelectDiscoveredTools(saved.revision, saved.revision)) return fail(
            ToolProblemCode.CONFIGURATION_CHANGED, "请先保存连接配置的修改，再检查该版本的工具。"
        )
        if (discoveryJob?.isActive == true || discovery.value[saved.id]?.loading == true) return fail(
            ToolProblemCode.INVALID_CONFIGURATION, "此服务的检查正在进行，请停止本页检查或等待其他任务完成。"
        )
        val generation = ++discoveryGeneration
        _state.value = _state.value.copy(checking = true, discoveryCancelled = false, problem = null)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                remoteMcp.discover(saved.id, saved.revision)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ToolException) {
                if (generation == discoveryGeneration) {
                    _state.value = _state.value.copy(problem = failure.problem)
                }
            } finally {
                if (generation == discoveryGeneration) {
                    discoveryJob = null
                    _state.value = _state.value.copy(checking = false)
                }
            }
        }
        discoveryJob = job
        job.start()
    }

    fun cancelDiscovery() {
        val job = discoveryJob ?: return
        discoveryGeneration++
        discoveryJob = null
        job.cancel()
        _state.value = _state.value.copy(checking = false, discoveryCancelled = true)
    }

    fun setToolSelected(name: String, selected: Boolean) {
        if (!available()) return
        val form = _state.value.server ?: return notReady()
        val snapshot = readySnapshot() ?: return
        val saved = snapshot.servers.firstOrNull { it.id == form.serverId } ?: return configurationChanged()
        if (form.isStale(saved)) return configurationChanged()
        if (selected) {
            val report = currentToolDiscovery(saved, discovery.value[saved.id])
            val tool = report?.tools?.singleOrNull { it.name == name }
            if (!form.canSelectDiscoveredTools(saved.revision, saved.revision) || report == null) return fail(
                ToolProblemCode.CONFIGURATION_CHANGED, "发现结果不可用于当前草稿，请保存并重新检查当前配置。"
            )
            if (tool?.canBeSelected() != true) return fail(
                ToolProblemCode.SCHEMA, "此工具未通过当前版本的 schema 检查，不能启用。"
            )
            if (name !in form.draft.enabledTools && form.draft.enabledTools.size >= ToolSettingsLimits.MAX_SELECTED_TOOLS) {
                return fail(ToolProblemCode.INVALID_CONFIGURATION, "最多选择 64 个工具，请先移除其他选择。")
            }
        }
        val selectedTools = if (selected) form.draft.enabledTools + name else form.draft.enabledTools - name
        _state.value = _state.value.copy(
            server = form.copy(draft = form.draft.copy(enabledTools = selectedTools)), problem = null, savedNotice = false
        )
    }

    private fun validateNewToolSelections(form: ToolMcpForm, saved: McpServerSettings?): Boolean {
        val added = form.draft.enabledTools - form.original?.enabledTools.orEmpty()
        if (added.isEmpty()) return true
        val report = currentToolDiscovery(saved, saved?.id?.let { discovery.value[it] })
        if (saved == null || report == null || !form.canSelectDiscoveredTools(report.configRevision, saved.revision)) {
            configurationChanged()
            return false
        }
        if (added.any { name -> report.tools.singleOrNull { it.name == name }?.canBeSelected() != true }) {
            fail(ToolProblemCode.SCHEMA, "只能启用当前配置版本中已通过 schema 检查的工具。")
            return false
        }
        return true
    }

    private fun launchMutation(action: ToolSettingsPending, mutation: suspend () -> Unit) {
        _state.value = _state.value.copy(pending = action, problem = null, savedNotice = false)
        viewModelScope.launch {
            try {
                mutation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ToolException) {
                _state.value = _state.value.copy(problem = failure.problem)
            } finally {
                _state.value = _state.value.copy(pending = null)
            }
        }
    }

    private fun receiveConfiguration(force: Boolean = false) {
        if (!force && _state.value.pending == ToolSettingsPending.RELOAD) return
        val configuration = settings.value
        if (configuration.loading || configuration.problem != null) return
        val snapshot = configuration.snapshot ?: return
        when (val target = editor) {
            Editor.Search -> if (_state.value.search == null) {
                _state.value = _state.value.copy(search = ToolSearchForm(snapshot.web))
            }
            is Editor.Server -> if (_state.value.server == null && !_state.value.deleted) {
                val saved = snapshot.servers.firstOrNull { it.id == target.id }
                if (target.id != null && saved == null) {
                    fail(ToolProblemCode.CONFIGURATION_CHANGED, "此 MCP 服务已不存在，请返回工具列表。")
                } else {
                    _state.value = _state.value.copy(server = ToolMcpForm(target.id, saved))
                }
            }
            null -> Unit
        }
    }

    private fun available(): Boolean {
        if (_state.value.pending == null) return true
        fail(ToolProblemCode.INVALID_CONFIGURATION, "另一项设置操作正在进行，请稍后重试。")
        return false
    }

    private fun readySnapshot(): ToolSettingsSnapshot? {
        val current = settings.value
        current.problem?.let {
            _state.value = _state.value.copy(problem = it)
            return null
        }
        if (current.loading || current.snapshot == null) {
            notReady()
            return null
        }
        return current.snapshot
    }

    private fun notReady() = fail(ToolProblemCode.INVALID_CONFIGURATION, "工具配置尚不可用，请重新读取后重试。")
    private fun configurationChanged() = fail(
        ToolProblemCode.CONFIGURATION_CHANGED, "配置已更新或删除；草稿已保留，请放弃草稿并重新读取，不能覆盖新版本。"
    )
    private fun fail(code: ToolProblemCode, message: String) {
        _state.value = _state.value.copy(problem = ToolProblem(code, message))
    }

    override fun onCleared() {
        cancelDiscovery()
        super.onCleared()
    }

    private sealed interface Editor {
        data object Search : Editor
        data class Server(val routeId: String?, val id: String? = routeId) : Editor
    }

    private companion object {
        val SERVER_ID = Regex("[0-9a-f]{16}")
        fun credentialMatches(action: ToolCredentialAction, value: CredentialUpdate): Boolean = when (action) {
            ToolCredentialAction.KEEP -> value is CredentialUpdate.Keep
            ToolCredentialAction.REPLACE -> value is CredentialUpdate.Replace
            ToolCredentialAction.REMOVE -> value is CredentialUpdate.Remove
        }
    }
}
