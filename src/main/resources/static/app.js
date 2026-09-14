// SuperBizAgent 前端应用
class SuperBizAgentApp {
    constructor() {
        this.apiBaseUrl = 'http://localhost:9900/api';
        this.currentProvider = this.loadProvider(); // 'deepseek' 或 'dashscope'
        this.sessionId = this.generateSessionId();
        this.isStreaming = false;
        this.isUploading = false;
        this.pendingAttachments = [];
        this.currentChatHistory = []; // 当前对话的消息历史
        this.chatHistories = this.loadChatHistories(); // 所有历史对话
        this.isCurrentChatFromHistory = false; // 标记当前对话是否是从历史记录加载的
        
        this.initializeElements();
        this.bindEvents();
        this.updateUI();
        this.initMarkdown();
        this.checkAndSetCentered();
        this.renderChatHistory();
    }

    // 初始化Markdown配置
    initMarkdown() {
        // 等待 marked 库加载完成
        const checkMarked = () => {
            if (typeof marked !== 'undefined') {
                try {
                    // 配置marked选项
                    marked.setOptions({
                        breaks: true,  // 支持GFM换行
                        gfm: true,     // 启用GitHub风格的Markdown
                        headerIds: false,
                        mangle: false
                    });

                    // 配置代码高亮
                    if (typeof hljs !== 'undefined') {
                        marked.setOptions({
                            highlight: function(code, lang) {
                                if (lang && hljs.getLanguage(lang)) {
                                    try {
                                        return hljs.highlight(code, { language: lang }).value;
                                    } catch (err) {
                                        console.error('代码高亮失败:', err);
                                    }
                                }
                                return code;
                            }
                        });
                    }
                    console.log('Markdown 渲染库初始化成功');
                } catch (e) {
                    console.error('Markdown 配置失败:', e);
                }
            } else {
                // 如果 marked 还没加载，等待一段时间后重试
                setTimeout(checkMarked, 100);
            }
        };
        checkMarked();
    }

    // 安全地渲染 Markdown
    renderMarkdown(content) {
        if (!content) return '';
        
        // 检查 marked 是否可用
        if (typeof marked === 'undefined') {
            console.warn('marked 库未加载，使用纯文本显示');
            return this.escapeHtml(content);
        }
        
        try {
            const html = marked.parse(content);
            return html;
        } catch (e) {
            console.error('Markdown 渲染失败:', e);
            return this.escapeHtml(content);
        }
    }

    // 高亮代码块
    highlightCodeBlocks(container) {
        if (typeof hljs !== 'undefined' && container) {
            try {
                container.querySelectorAll('pre code').forEach((block) => {
                    if (!block.classList.contains('hljs')) {
                        hljs.highlightElement(block);
                    }
                });
            } catch (e) {
                console.error('代码高亮失败:', e);
            }
        }
    }

    // 初始化DOM元素
    initializeElements() {
        // 侧边栏元素
        this.sidebar = document.querySelector('.sidebar');
        this.newChatBtn = document.getElementById('newChatBtn');
        this.aiOpsSidebarBtn = document.getElementById('aiOpsSidebarBtn');
        this.markValuableBtn = document.getElementById('markValuableBtn');
        this.markValuableBar = document.getElementById('markValuableBar');
        
        // 输入区域元素
        this.messageInput = document.getElementById('messageInput');
        this.sendButton = document.getElementById('sendButton');
        this.toolsBtn = document.getElementById('toolsBtn');
        this.toolsMenu = document.getElementById('toolsMenu');
        this.uploadFileItem = document.getElementById('uploadFileItem');
        this.modeSelectorBtn = document.getElementById('modeSelectorBtn');
        this.modeDropdown = document.getElementById('modeDropdown');
        this.currentModeText = document.getElementById('currentModeText');
        this.fileInput = document.getElementById('fileInput');
        this.attachmentChips = document.getElementById('attachmentChips');
        
        // 聊天区域元素
        this.chatMessages = document.getElementById('chatMessages');
        this.loadingOverlay = document.getElementById('loadingOverlay');
        this.chatContainer = document.querySelector('.chat-container');
        this.welcomeGreeting = document.getElementById('welcomeGreeting');
        this.chatHistoryList = document.getElementById('chatHistoryList');
        
        // 初始化时检查是否需要居中
        this.checkAndSetCentered();
    }

    // 绑定事件监听器
    bindEvents() {
        // 新建对话
        if (this.newChatBtn) {
            this.newChatBtn.addEventListener('click', () => this.newChat());
        }
        
        // AI Ops按钮
        if (this.aiOpsSidebarBtn) {
            this.aiOpsSidebarBtn.addEventListener('click', () => this.triggerAIOps());
        }

        if (this.markValuableBtn) {
            this.markValuableBtn.addEventListener('click', () => this.markCurrentChatValuable());
        }
        
        // 模式选择下拉菜单
        if (this.modeSelectorBtn) {
            this.modeSelectorBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                this.toggleModeDropdown();
            });
        }
        
        // 模型选择：事件委托，点击子节点也能取到 data-provider
        if (this.modeDropdown) {
            this.modeDropdown.addEventListener('click', (e) => {
                const item = e.target.closest('.dropdown-item');
                if (!item) {
                    return;
                }
                e.stopPropagation();
                this.selectProvider(item.getAttribute('data-provider'));
                this.closeModeDropdown();
            });
        }
        
        // 点击外部关闭下拉菜单
        document.addEventListener('click', (e) => {
            if (!this.modeSelectorBtn.contains(e.target) && 
                !this.modeDropdown.contains(e.target)) {
                this.closeModeDropdown();
            }
        });
        
        // 发送消息
        if (this.sendButton) {
            this.sendButton.addEventListener('click', () => this.sendMessage());
        }
        
        if (this.messageInput) {
            this.messageInput.addEventListener('keypress', (e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    this.sendMessage();
                }
            });
        }
        
        // 工具按钮和菜单
        if (this.toolsBtn) {
            this.toolsBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                this.toggleToolsMenu();
            });
        }
        
        // 工具菜单项点击事件
        if (this.uploadFileItem) {
            this.uploadFileItem.addEventListener('click', () => {
                if (this.fileInput) {
                    this.fileInput.click();
                }
                this.closeToolsMenu();
            });
        }
        
        // 点击外部关闭工具菜单
        document.addEventListener('click', (e) => {
            if (this.toolsBtn && this.toolsMenu && 
                !this.toolsBtn.contains(e.target) && 
                !this.toolsMenu.contains(e.target)) {
                this.closeToolsMenu();
            }
        });
        
        if (this.fileInput) {
            this.fileInput.addEventListener('change', (e) => this.handleFileSelect(e));
        }
    }

    // 切换工具菜单显示/隐藏
    toggleToolsMenu() {
        if (this.toolsMenu && this.toolsBtn) {
            const wrapper = this.toolsBtn.closest('.tools-btn-wrapper');
            if (wrapper) {
                wrapper.classList.toggle('active');
            }
        }
    }

    // 关闭工具菜单
    closeToolsMenu() {
        if (this.toolsMenu && this.toolsBtn) {
            const wrapper = this.toolsBtn.closest('.tools-btn-wrapper');
            if (wrapper) {
                wrapper.classList.remove('active');
            }
        }
    }

    // 新建对话
    newChat() {
        if (this.isStreaming) {
            this.showNotification('请等待当前对话完成后再新建对话', 'warning');
            return;
        }
        
        // 如果当前有对话内容，且不是从历史记录加载的，才保存为新的历史对话
        // 如果是从历史记录加载的，只需要更新该历史记录
        if (this.currentChatHistory.length > 0) {
            if (this.isCurrentChatFromHistory) {
                // 当前对话是从历史记录加载的，更新该历史记录
                this.updateCurrentChatHistory();
            } else {
                // 当前对话是新对话，保存为新的历史对话
                this.saveCurrentChat();
            }
        }
        
        // 停止所有进行中的操作
        this.isStreaming = false;
        this.isUploading = false;
        
        // 清空输入框
        if (this.messageInput) {
            this.messageInput.value = '';
        }
        this.clearPendingAttachments(false);
        
        // 清空当前对话历史
        this.currentChatHistory = [];
        
        // 重置标记
        this.isCurrentChatFromHistory = false;
        
        // 清空聊天记录
        if (this.chatMessages) {
            this.chatMessages.innerHTML = '';
        }
        
        // 生成新的会话ID
        this.sessionId = this.generateSessionId();
        
        this.updateUI();
        
        // 重新设置居中样式（确保对话框居中显示）
        this.checkAndSetCentered();
        
        // 确保容器有过渡动画
        if (this.chatContainer) {
            this.chatContainer.style.transition = 'all 0.5s ease';
        }
        
        // 更新历史对话列表
        this.renderChatHistory();
    }
    
    // 保存当前对话到历史记录（新建）
    saveCurrentChat() {
        if (this.currentChatHistory.length === 0) {
            return;
        }
        
        // 检查是否已存在相同ID的历史记录
        const existingIndex = this.chatHistories.findIndex(h => h.id === this.sessionId);
        if (existingIndex !== -1) {
            // 如果已存在，更新而不是新建
            this.updateCurrentChatHistory();
            return;
        }
        
        // 获取对话标题（使用第一条用户消息的前30个字符）
        const firstUserMessage = this.currentChatHistory.find(msg => msg.type === 'user');
        const title = firstUserMessage ? 
            (firstUserMessage.content.substring(0, 30) + (firstUserMessage.content.length > 30 ? '...' : '')) : 
            '新对话';
        
        const chatHistory = {
            id: this.sessionId,
            title: title,
            titleCustom: false,
            messages: [...this.currentChatHistory],
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString()
        };
        
        // 添加到历史记录列表的开头
        this.chatHistories.unshift(chatHistory);
        
        // 限制历史记录数量（最多保存50条）
        if (this.chatHistories.length > 50) {
            this.chatHistories = this.chatHistories.slice(0, 50);
        }
        
        // 保存到localStorage
        this.saveChatHistories();
        this.isCurrentChatFromHistory = true;
    }

    // 将当前对话写入侧边栏：新对话首次保存，已有条目则更新
    persistCurrentConversation() {
        if (this.currentChatHistory.length === 0) {
            return;
        }
        const exists = this.chatHistories.some(h => h.id === this.sessionId);
        if (exists) {
            this.updateCurrentChatHistory();
        } else {
            this.saveCurrentChat();
        }
        this.isCurrentChatFromHistory = true;
        this.renderChatHistory();
    }
    
    // 更新当前对话的历史记录
    updateCurrentChatHistory() {
        if (this.currentChatHistory.length === 0) {
            return;
        }
        
        const existingIndex = this.chatHistories.findIndex(h => h.id === this.sessionId);
        if (existingIndex === -1) {
            // 如果不存在，调用保存方法
            this.saveCurrentChat();
            return;
        }
        
        // 更新现有的历史记录
        const history = this.chatHistories[existingIndex];
        history.messages = [...this.currentChatHistory];
        history.updatedAt = new Date().toISOString();
        
        // 未手动重命名时，用第一条用户消息作为标题
        if (!history.titleCustom) {
            const firstUserMessage = this.currentChatHistory.find(msg => msg.type === 'user');
            if (firstUserMessage) {
                const newTitle = firstUserMessage.content.substring(0, 30) + (firstUserMessage.content.length > 30 ? '...' : '');
                if (history.title !== newTitle) {
                    history.title = newTitle;
                }
            }
        }
        
        // 保存到localStorage
        this.saveChatHistories();
    }
    
    // 加载历史对话列表
    loadChatHistories() {
        try {
            const stored = localStorage.getItem('chatHistories');
            return stored ? JSON.parse(stored) : [];
        } catch (e) {
            console.error('加载历史对话失败:', e);
            return [];
        }
    }
    
    // 保存历史对话列表到localStorage
    saveChatHistories() {
        try {
            localStorage.setItem('chatHistories', JSON.stringify(this.chatHistories));
        } catch (e) {
            console.error('保存历史对话失败:', e);
        }
    }
    
    // 渲染历史对话列表
    renderChatHistory() {
        if (!this.chatHistoryList) {
            return;
        }
        
        this.chatHistoryList.innerHTML = '';
        
        if (this.chatHistories.length === 0) {
            return;
        }
        
        this.chatHistories.forEach((history, index) => {
            const historyItem = document.createElement('div');
            historyItem.className = 'history-item' + (history.id === this.sessionId ? ' active' : '');
            historyItem.dataset.historyId = history.id;
            
            historyItem.innerHTML = `
                <div class="history-item-content">
                    <span class="history-item-title">${this.escapeHtml(history.title)}</span>
                </div>
                <div class="history-item-actions">
                    <button class="history-item-rename" data-history-id="${history.id}" title="重命名">
                        <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                            <path d="M12 20h9" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
                            <path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                        </svg>
                    </button>
                    <button class="history-item-delete" data-history-id="${history.id}" title="删除">
                        <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                            <path d="M18 6L6 18M6 6L18 18" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
                        </svg>
                    </button>
                </div>
            `;
            
            // 点击历史项加载对话
            historyItem.addEventListener('click', (e) => {
                if (!e.target.closest('.history-item-actions') && !e.target.closest('.history-item-rename-input')) {
                    this.loadChatHistory(history.id);
                }
            });

            const renameBtn = historyItem.querySelector('.history-item-rename');
            renameBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                this.startRenameChat(history.id, historyItem);
            });
            
            // 删除历史对话
            const deleteBtn = historyItem.querySelector('.history-item-delete');
            deleteBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                this.deleteChatHistory(history.id);
            });
            
            this.chatHistoryList.appendChild(historyItem);
        });
    }
    
    // 加载历史对话
    loadChatHistory(historyId) {
        const history = this.chatHistories.find(h => h.id === historyId);
        if (!history) {
            return;
        }
        
        // 如果当前有对话内容，且不是同一个对话，先保存
        if (this.currentChatHistory.length > 0 && this.sessionId !== historyId) {
            if (this.isCurrentChatFromHistory) {
                // 如果当前对话也是从历史记录加载的，更新它
                this.updateCurrentChatHistory();
            } else {
                // 如果当前对话是新对话，保存为新历史
                this.saveCurrentChat();
            }
        }
        
        // 加载历史对话
        this.sessionId = history.id;
        this.currentChatHistory = [...history.messages];
        this.isCurrentChatFromHistory = true; // 标记为从历史记录加载
        this.clearPendingAttachments(false);
        
        // 清空并重新渲染消息
        if (this.chatMessages) {
            this.chatMessages.innerHTML = '';
            history.messages.forEach(msg => {
                this.addMessage(msg.type, msg.content, false, false, msg.attachments || []);
            });
        }
        
        // 更新UI
        this.checkAndSetCentered();
        this.renderChatHistory();
        this.updateUI();
    }
    
    // 删除历史对话
    deleteChatHistory(historyId) {
        this.chatHistories = this.chatHistories.filter(h => h.id !== historyId);
        this.saveChatHistories();
        this.renderChatHistory();

        // 同步后端：清空该会话的服务端历史与滚动摘要（尽力而为，失败不影响前端删除）
        fetch(`${this.apiBaseUrl}/chat/clear`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ Id: historyId })
        }).catch(() => {});
        
        // 如果删除的是当前对话，清空当前对话
        if (this.sessionId === historyId) {
            this.currentChatHistory = [];
            this.isCurrentChatFromHistory = false;
            if (this.chatMessages) {
                this.chatMessages.innerHTML = '';
            }
            this.clearPendingAttachments(false);
            this.sessionId = this.generateSessionId();
            this.checkAndSetCentered();
            this.updateUI();
        }
    }

    startRenameChat(historyId, historyItem) {
        const history = this.chatHistories.find(h => h.id === historyId);
        if (!history) {
            return;
        }
        const titleSpan = historyItem.querySelector('.history-item-title');
        if (!titleSpan) {
            return;
        }

        historyItem.classList.add('renaming');
        const input = document.createElement('input');
        input.type = 'text';
        input.className = 'history-item-rename-input';
        input.value = history.title || '';
        input.maxLength = 40;

        let finished = false;
        const commit = () => {
            if (finished) {
                return;
            }
            finished = true;
            const next = input.value.trim();
            if (next) {
                history.title = next;
                history.titleCustom = true;
                this.saveChatHistories();
            }
            this.renderChatHistory();
        };
        const cancel = () => {
            if (finished) {
                return;
            }
            finished = true;
            this.renderChatHistory();
        };

        input.addEventListener('click', (e) => e.stopPropagation());
        input.addEventListener('mousedown', (e) => e.stopPropagation());
        input.addEventListener('keydown', (e) => {
            e.stopPropagation();
            if (e.key === 'Enter') {
                e.preventDefault();
                commit();
            } else if (e.key === 'Escape') {
                e.preventDefault();
                cancel();
            }
        });
        input.addEventListener('blur', commit);

        titleSpan.replaceWith(input);
        input.focus();
        input.select();
    }

    async markCurrentChatValuable() {
        if (this.isStreaming) {
            this.showNotification('请等待当前对话完成后再标记', 'warning');
            return;
        }
        if (this.currentChatHistory.length === 0) {
            this.showNotification('当前对话为空，无法标记有价值', 'warning');
            return;
        }

        this.persistCurrentConversation();
        if (this.markValuableBtn) {
            this.markValuableBtn.disabled = true;
        }
        try {
            const response = await fetch(`${this.apiBaseUrl}/experience/mark`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId: this.sessionId })
            });
            const data = await response.json();
            if (data.code === 200 || data.message === 'success') {
                this.showNotification(data.data || '已提交经验提炼', 'success');
            } else {
                this.showNotification(data.message || '标记失败', 'error');
            }
        } catch (error) {
            console.error('标记有价值失败:', error);
            this.showNotification('标记失败: ' + error.message, 'error');
        } finally {
            this.updateUI();
        }
    }

    updateMarkValuableVisibility() {
        const last = this.currentChatHistory.length > 0
            ? this.currentChatHistory[this.currentChatHistory.length - 1]
            : null;
        const show = !this.isStreaming && last && last.type === 'assistant';
        if (this.markValuableBar) {
            this.markValuableBar.hidden = !show;
        }
        if (this.markValuableBtn) {
            this.markValuableBtn.disabled = !show;
        }
    }

    // 切换模式下拉菜单
    toggleModeDropdown() {
        if (this.modeSelectorBtn && this.modeDropdown) {
            const wrapper = this.modeSelectorBtn.closest('.mode-selector-wrapper');
            if (wrapper) {
                wrapper.classList.toggle('active');
            }
        }
    }

    // 关闭模式下拉菜单
    closeModeDropdown() {
        if (this.modeSelectorBtn && this.modeDropdown) {
            const wrapper = this.modeSelectorBtn.closest('.mode-selector-wrapper');
            if (wrapper) {
                wrapper.classList.remove('active');
            }
        }
    }

    // 选择模型
    selectProvider(provider) {
        if (this.isStreaming) {
            this.showNotification('请等待当前对话完成后再切换模型', 'warning');
            return;
        }
        const resolved = this.normalizeProvider(provider);
        if (!resolved) {
            this.showNotification('模型切换失败，请重新选择 DeepSeek 或通义千问', 'warning');
            return;
        }

        this.currentProvider = resolved;
        this.saveProvider(resolved);
        this.updateUI();

        const providerNames = {
            'deepseek': 'DeepSeek',
            'dashscope': '通义千问'
        };
        this.showNotification(`已切换到${providerNames[resolved]}`, 'info');
    }

    normalizeProvider(provider) {
        if (provider === 'deepseek' || provider === 'dashscope') {
            return provider;
        }
        if (provider === 'qwen' || provider === 'aliyun' || provider === 'alibaba') {
            return 'dashscope';
        }
        return null;
    }

    loadProvider() {
        try {
            const saved = localStorage.getItem('oncall.llm.provider');
            const resolved = this.normalizeProvider(saved);
            if (resolved) {
                return resolved;
            }
        } catch (e) {
            console.warn('读取模型选择失败', e);
        }
        return 'deepseek';
    }

    saveProvider(provider) {
        try {
            localStorage.setItem('oncall.llm.provider', provider);
        } catch (e) {
            console.warn('保存模型选择失败', e);
        }
    }

    // 更新UI
    updateUI() {
        // 更新模型选择器显示
        if (this.currentModeText) {
            const providerNames = {
                'deepseek': 'DeepSeek',
                'dashscope': '通义千问'
            };
            this.currentModeText.textContent = providerNames[this.currentProvider] || 'DeepSeek';
        }
        
        // 更新下拉菜单选中状态
        const dropdownItems = document.querySelectorAll('.dropdown-item');
        dropdownItems.forEach(item => {
            const provider = item.getAttribute('data-provider');
            if (provider === this.currentProvider) {
                item.classList.add('active');
            } else {
                item.classList.remove('active');
            }
        });
        
        // 更新发送按钮状态
        if (this.sendButton) {
            this.sendButton.disabled = this.isStreaming || this.isUploading;
        }

        if (this.markValuableBtn) {
            this.markValuableBtn.disabled = this.isStreaming || this.currentChatHistory.length === 0;
        }
        this.updateMarkValuableVisibility();
        
        // 更新输入框状态
        if (this.messageInput) {
            this.messageInput.disabled = this.isStreaming;
            this.messageInput.placeholder = '问问智能OnCall助手';
        }
        if (this.uploadFileItem) {
            this.uploadFileItem.style.pointerEvents = (this.isStreaming || this.isUploading) ? 'none' : '';
            this.uploadFileItem.style.opacity = (this.isStreaming || this.isUploading) ? '0.5' : '';
        }
    }

    // 生成随机会话ID
    generateSessionId() {
        return 'session_' + Math.random().toString(36).substr(2, 9) + '_' + Date.now();
    }

    // 发送消息
    async sendMessage() {
        let message = '';
        if (this.messageInput) {
            message = this.messageInput.value.trim();
        }
        const attachmentIds = this.pendingAttachments.map(item => item.id);
        
        if (!message && attachmentIds.length === 0) {
            this.showNotification('请输入消息或添加附件', 'warning');
            return;
        }

        if (this.isStreaming || this.isUploading) {
            this.showNotification(this.isUploading ? '请等待附件上传完成' : '请等待当前对话完成', 'warning');
            return;
        }

        const displayText = message || '请阅读附件';
        this.addMessage('user', displayText, false, true, [...this.pendingAttachments]);
        
        if (this.messageInput) {
            this.messageInput.value = '';
        }
        this.clearPendingAttachments(false);

        this.isStreaming = true;
        this.updateUI();

        try {
            await this.sendStreamMessage(message, attachmentIds);
        } catch (error) {
            console.error('发送消息失败:', error);
            this.addMessage('assistant', '抱歉，发送消息时出现错误：' + error.message);
        } finally {
            this.isStreaming = false;
            this.updateUI();
            this.persistCurrentConversation();
        }
    }

    // 发送快速消息（普通对话）
    async sendQuickMessage(message) {
        // 添加等待提示消息
        const loadingMessage = this.addLoadingMessage('正在思考...');
        
        try {
            const response = await fetch(`${this.apiBaseUrl}/chat`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({
                    Id: this.sessionId,
                    Question: message,
                    Provider: this.currentProvider
                })
            });

            if (!response.ok) {
                throw new Error(`HTTP错误: ${response.status}`);
            }

            const data = await response.json();
            console.log('[sendQuickMessage] 响应数据:', JSON.stringify(data));
            
            // 移除等待提示消息
            if (loadingMessage && loadingMessage.parentNode) {
                loadingMessage.parentNode.removeChild(loadingMessage);
            }
            
            // 统一响应格式：检查 data.code 或 data.message 判断请求是否成功
            if (data.code === 200 || data.message === 'success') {
                // data.data 是 ChatResponse 对象
                const chatResponse = data.data;
                
                if (chatResponse && chatResponse.success) {
                    // 成功：添加实际响应消息（即使 answer 为空也显示）
                    const answer = chatResponse.answer || '（无回复内容）';
                    this.addMessage('assistant', answer);
                } else if (chatResponse && chatResponse.errorMessage) {
                    // 业务错误
                    throw new Error(chatResponse.errorMessage);
                } else {
                    // 兜底：尝试显示任何可用内容
                    const fallbackAnswer = chatResponse?.answer || chatResponse?.errorMessage || '服务返回了空内容';
                    this.addMessage('assistant', fallbackAnswer);
                }
            } else {
                // HTTP 成功但业务失败
                throw new Error(data.message || '请求失败');
            }
        } catch (error) {
            // 出错时也要移除等待提示消息
            if (loadingMessage && loadingMessage.parentNode) {
                loadingMessage.parentNode.removeChild(loadingMessage);
            }
            throw error;
        }
    }

    // 发送流式消息
    async sendStreamMessage(message, attachmentIds = []) {
        try {
            const response = await fetch(`${this.apiBaseUrl}/chat_stream`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({
                    Id: this.sessionId,
                    Question: message,
                    Provider: this.currentProvider,
                    AttachmentIds: attachmentIds
                })
            });

            if (!response.ok) {
                throw new Error(`HTTP错误: ${response.status}`);
            }
            
            // 创建助手消息元素
            const assistantMessageElement = this.addMessage('assistant', '', true);
            let fullResponse = '';

            // 处理流式响应
            const reader = response.body.getReader();
            const decoder = new TextDecoder();
            let buffer = '';
            let currentEvent = '';

            try {
                while (true) {
                    const { done, value } = await reader.read();
                    
                    if (done) {
                        // 流结束，使用统一的处理方法
                        this.handleStreamComplete(assistantMessageElement, fullResponse);
                        break;
                    }

                    // 解码数据并添加到缓冲区
                    buffer += decoder.decode(value, { stream: true });
                    
                    // 按行分割处理
                    const lines = buffer.split('\n');
                    // 保留最后一行（可能不完整）
                    buffer = lines.pop() || '';
                    
                    for (const line of lines) {
                        if (line.trim() === '') continue;
                        
                        console.log('[SSE调试] 收到行:', line);
                        
                        // 解析SSE格式
                        if (line.startsWith('id:')) {
                            console.log('[SSE调试] 解析到ID');
                            continue;
                        } else if (line.startsWith('event:')) {
                            // 兼容 "event:message" 和 "event: message" 两种格式
                            currentEvent = line.substring(6).trim();
                            console.log('[SSE调试] 解析到事件类型:', currentEvent);
                            // 注意：后端统一使用 "message" 事件名，真正的类型在 data 的 JSON 中
                            continue;
                        } else if (line.startsWith('data:')) {
                            // 兼容 "data:xxx" 和 "data: xxx" 两种格式
                            const rawData = line.substring(5).trim();
                            console.log('[SSE调试] 解析到数据, currentEvent:', currentEvent, ', rawData:', rawData);
                            
                            // 兼容旧格式 [DONE] 标记
                            if (rawData === '[DONE]') {
                                // 流结束标记，将内容转换为Markdown渲染
                                this.handleStreamComplete(assistantMessageElement, fullResponse);
                                return;
                            }
                            
                            // 处理 SSE 数据
                            try {
                                // 尝试解析为 SseMessage 格式的 JSON
                                const sseMessage = JSON.parse(rawData);
                                console.log('[SSE调试] 解析JSON成功:', sseMessage);
                                
                                if (sseMessage && typeof sseMessage.type === 'string') {
                                    if (sseMessage.type === 'content') {
                                        const content = sseMessage.data || '';
                                        fullResponse += content;
                                        console.log('[SSE调试] 添加内容:', content);
                                        
                                        // 实时渲染 Markdown
                                        if (assistantMessageElement) {
                                            const messageContent = assistantMessageElement.querySelector('.message-content');
                                            messageContent.innerHTML = this.renderMarkdown(fullResponse);
                                            // 高亮代码块
                                            this.highlightCodeBlocks(messageContent);
                                            this.scrollToBottom();
                                        }
                                    } else if (sseMessage.type === 'done') {
                                        console.log('[SSE调试] 收到done标记，流结束');
                                        this.handleStreamComplete(assistantMessageElement, fullResponse);
                                        return;
                                    } else if (sseMessage.type === 'error') {
                                        console.error('[SSE调试] 收到错误:', sseMessage.data);
                                        if (assistantMessageElement) {
                                            const messageContent = assistantMessageElement.querySelector('.message-content');
                                            messageContent.innerHTML = this.renderMarkdown('错误: ' + (sseMessage.data || '未知错误'));
                                        }
                                        return;
                                    }
                                } else {
                                    // 不是标准 SseMessage 格式，尝试兼容处理
                                    console.log('[SSE调试] 非标准格式，尝试兼容处理');
                                    fullResponse += rawData;
                                    if (assistantMessageElement) {
                                        const messageContent = assistantMessageElement.querySelector('.message-content');
                                        messageContent.innerHTML = this.renderMarkdown(fullResponse);
                                        this.highlightCodeBlocks(messageContent);
                                        this.scrollToBottom();
                                    }
                                }
                            } catch (e) {
                                // JSON 解析失败，尝试兼容旧格式
                                console.log('[SSE调试] JSON解析失败，使用兼容模式:', e.message);
                                if (rawData === '') {
                                    fullResponse += '\n';
                                } else {
                                    fullResponse += rawData;
                                }
                                
                                if (assistantMessageElement) {
                                    const messageContent = assistantMessageElement.querySelector('.message-content');
                                    messageContent.innerHTML = this.renderMarkdown(fullResponse);
                                    this.highlightCodeBlocks(messageContent);
                                    this.scrollToBottom();
                                }
                            }
                        }
                    }
                }
            } finally {
                reader.releaseLock();
            }
        } catch (error) {
            throw error;
        }
    }

    // 添加消息到聊天界面
    addMessage(type, content, isStreaming = false, saveToHistory = true, attachments = []) {
        // 检查是否是第一条消息，如果是则移除居中样式
        const isFirstMessage = this.chatMessages && this.chatMessages.querySelectorAll('.message').length === 0;
        
        // 保存消息到当前对话历史（如果不是流式消息且需要保存）
        if (!isStreaming && saveToHistory && (content || (attachments && attachments.length))) {
            this.currentChatHistory.push({
                type: type,
                content: content,
                attachments: attachments && attachments.length
                    ? attachments.map(item => ({ fileName: item.fileName, size: item.size }))
                    : undefined,
                timestamp: new Date().toISOString()
            });
        }
        
        const messageDiv = document.createElement('div');
        messageDiv.className = `message ${type}${isStreaming ? ' streaming' : ''}`;

        // 如果是assistant消息，添加头像图标
        if (type === 'assistant') {
            const messageAvatar = document.createElement('div');
            messageAvatar.className = 'message-avatar';
            messageAvatar.innerHTML = `
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path d="M12 2L15.09 8.26L22 9.27L17 14.14L18.18 21.02L12 17.77L5.82 21.02L7 14.14L2 9.27L8.91 8.26L12 2Z" fill="white"/>
                </svg>
            `;
            messageDiv.appendChild(messageAvatar);
        }

        // 创建消息内容包装器
        const messageContentWrapper = document.createElement('div');
        messageContentWrapper.className = 'message-content-wrapper';

        if (type === 'user' && attachments && attachments.length) {
            const filesRow = document.createElement('div');
            filesRow.className = 'message-attachments';
            attachments.forEach(item => {
                const chip = document.createElement('span');
                chip.className = 'message-attachment-chip';
                chip.textContent = item.fileName || '附件';
                filesRow.appendChild(chip);
            });
            messageContentWrapper.appendChild(filesRow);
        }

        const messageContent = document.createElement('div');
        messageContent.className = 'message-content';
        
        // 如果是assistant消息且不是流式消息，使用Markdown渲染
        if (type === 'assistant' && !isStreaming) {
            messageContent.innerHTML = this.renderMarkdown(content);
            // 高亮代码块
            this.highlightCodeBlocks(messageContent);
        } else {
            // 用户消息或流式消息使用纯文本
            messageContent.textContent = content;
        }

        messageContentWrapper.appendChild(messageContent);
        messageDiv.appendChild(messageContentWrapper);

        if (this.chatMessages) {
            this.chatMessages.appendChild(messageDiv);
            
            // 如果是第一条消息，移除居中样式并添加动画
            if (isFirstMessage && this.chatContainer) {
                this.chatContainer.classList.remove('centered');
                // 添加动画类
                this.chatContainer.style.transition = 'all 0.5s ease';
            }
            
            this.scrollToBottom();
        }

        return messageDiv;
    }

    // 添加带加载动画的消息
    addLoadingMessage(content) {
        const messageDiv = document.createElement('div');
        messageDiv.className = 'message assistant';

        // 添加头像图标
        const messageAvatar = document.createElement('div');
        messageAvatar.className = 'message-avatar';
        messageAvatar.innerHTML = `
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M12 2L15.09 8.26L22 9.27L17 14.14L18.18 21.02L12 17.77L5.82 21.02L7 14.14L2 9.27L8.91 8.26L12 2Z" fill="white"/>
            </svg>
        `;
        messageDiv.appendChild(messageAvatar);

        // 创建消息内容包装器
        const messageContentWrapper = document.createElement('div');
        messageContentWrapper.className = 'message-content-wrapper';

        const messageContent = document.createElement('div');
        messageContent.className = 'message-content loading-message-content';
        
        // 创建文本和动画容器
        const textSpan = document.createElement('span');
        textSpan.textContent = content;
        
        // 创建旋转动画图标
        const loadingIcon = document.createElement('span');
        loadingIcon.className = 'loading-spinner-icon';
        loadingIcon.innerHTML = `
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z" fill="currentColor" opacity="0.2"/>
                <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10c1.54 0 3-.36 4.28-1l-1.5-2.6C13.64 19.62 12.84 20 12 20c-4.41 0-8-3.59-8-8s3.59-8 8-8c.84 0 1.64.38 2.18 1l1.5-2.6C13 2.36 12.54 2 12 2z" fill="currentColor"/>
            </svg>
        `;
        
        messageContent.appendChild(textSpan);
        messageContent.appendChild(loadingIcon);
        messageContentWrapper.appendChild(messageContent);
        messageDiv.appendChild(messageContentWrapper);

        if (this.chatMessages) {
            this.chatMessages.appendChild(messageDiv);
            
            // 如果是第一条消息，移除居中样式
            const isFirstMessage = this.chatMessages.querySelectorAll('.message').length === 1;
            if (isFirstMessage && this.chatContainer) {
                this.chatContainer.classList.remove('centered');
                this.chatContainer.style.transition = 'all 0.5s ease';
            }
            
            this.scrollToBottom();
        }

        return messageDiv;
    }
    
    // 检查并设置居中样式
    checkAndSetCentered() {
        if (this.chatMessages && this.chatContainer) {
            const hasMessages = this.chatMessages.querySelectorAll('.message').length > 0;
            if (!hasMessages) {
                this.chatContainer.classList.add('centered');
            } else {
                this.chatContainer.classList.remove('centered');
            }
        }
    }

    // 滚动到底部
    scrollToBottom() {
        if (this.chatMessages) {
            this.chatMessages.scrollTop = this.chatMessages.scrollHeight;
        }
    }

    // 处理流式传输完成
    handleStreamComplete(assistantMessageElement, fullResponse) {
        if (assistantMessageElement) {
            assistantMessageElement.classList.remove('streaming');
            const messageContent = assistantMessageElement.querySelector('.message-content');
            if (messageContent) {
                messageContent.innerHTML = this.renderMarkdown(fullResponse);
                // 高亮代码块
                this.highlightCodeBlocks(messageContent);
            }
        }
        // 保存流式消息到历史记录，并立刻出现在「近期对话」
        if (fullResponse) {
            this.currentChatHistory.push({
                type: 'assistant',
                content: fullResponse,
                timestamp: new Date().toISOString()
            });
            this.persistCurrentConversation();
        }
    }

    // 显示通知
    showNotification(message, type = 'info') {
        // 创建通知元素
        const notification = document.createElement('div');
        notification.className = `notification ${type}`;
        notification.textContent = message;
        notification.style.cssText = `
            position: fixed;
            top: 20px;
            right: 20px;
            padding: 15px 20px;
            border-radius: 8px;
            color: white;
            font-weight: 500;
            z-index: 10000;
            animation: slideIn 0.3s ease;
            max-width: 300px;
        `;

        // 根据类型设置颜色（Google Material Design配色）
        const colors = {
            info: '#1a73e8',
            success: '#34a853',
            warning: '#fbbc04',
            error: '#ea4335'
        };
        notification.style.backgroundColor = colors[type] || colors.info;

        // 添加到页面
        document.body.appendChild(notification);

        // 3秒后自动移除
        setTimeout(() => {
            notification.style.animation = 'slideOut 0.3s ease';
            setTimeout(() => {
                if (notification.parentNode) {
                    notification.parentNode.removeChild(notification);
                }
            }, 300);
        }, 3000);
    }

    // 处理文件选择：上传为当前会话附件，不进入知识库
    handleFileSelect(event) {
        const files = Array.from(event.target.files || []);
        if (this.fileInput) {
            this.fileInput.value = '';
        }
        if (!files.length) {
            return;
        }
        files.forEach(file => this.uploadAttachment(file));
    }

    validateFileType(file) {
        const fileName = file.name.toLowerCase();
        const allowedExtensions = ['.txt', '.md', '.markdown', '.pdf', '.doc', '.docx'];
        return allowedExtensions.some(ext => fileName.endsWith(ext));
    }

    async uploadAttachment(file) {
        if (!this.validateFileType(file)) {
            this.showNotification('只支持 TXT、Markdown、PDF、Word 作为聊天附件', 'error');
            return;
        }
        const maxSize = 50 * 1024 * 1024;
        if (file.size > maxSize) {
            this.showNotification('文件大小不能超过50MB', 'error');
            return;
        }
        if (this.pendingAttachments.length >= 5) {
            this.showNotification('单轮最多附加 5 个文件', 'warning');
            return;
        }

        this.isUploading = true;
        this.updateUI();

        try {
            const formData = new FormData();
            formData.append('file', file);
            const response = await fetch(
                `${this.apiBaseUrl}/chat/attachments?sessionId=${encodeURIComponent(this.sessionId)}`,
                { method: 'POST', body: formData }
            );
            const data = await response.json().catch(() => ({}));
            if (!response.ok || (data.code && data.code !== 200)) {
                throw new Error(data.message || `HTTP错误: ${response.status}`);
            }
            const attachment = data.data;
            if (!attachment || !attachment.id) {
                throw new Error('附件上传响应无效');
            }
            this.pendingAttachments.push({
                id: attachment.id,
                fileName: attachment.fileName || file.name,
                size: attachment.size || file.size
            });
            this.renderPendingAttachments();
        } catch (error) {
            console.error('附件上传失败:', error);
            this.showNotification('附件上传失败: ' + error.message, 'error');
        } finally {
            this.isUploading = false;
            this.updateUI();
        }
    }

    renderPendingAttachments() {
        if (!this.attachmentChips) {
            return;
        }
        this.attachmentChips.innerHTML = '';
        if (!this.pendingAttachments.length) {
            this.attachmentChips.hidden = true;
            return;
        }
        this.attachmentChips.hidden = false;
        this.pendingAttachments.forEach(item => {
            const chip = document.createElement('div');
            chip.className = 'attachment-chip';
            const name = document.createElement('span');
            name.className = 'attachment-chip-name';
            name.textContent = item.fileName;
            name.title = item.fileName;
            const removeBtn = document.createElement('button');
            removeBtn.type = 'button';
            removeBtn.className = 'attachment-chip-remove';
            removeBtn.title = '移除附件';
            removeBtn.textContent = '×';
            removeBtn.addEventListener('click', (e) => {
                e.preventDefault();
                this.removePendingAttachment(item.id);
            });
            chip.appendChild(name);
            chip.appendChild(removeBtn);
            this.attachmentChips.appendChild(chip);
        });
    }

    async removePendingAttachment(attachmentId) {
        const kept = this.pendingAttachments.filter(item => item.id !== attachmentId);
        this.pendingAttachments = kept;
        this.renderPendingAttachments();
        try {
            await fetch(
                `${this.apiBaseUrl}/chat/attachments/${encodeURIComponent(attachmentId)}?sessionId=${encodeURIComponent(this.sessionId)}`,
                { method: 'DELETE' }
            );
        } catch (error) {
            console.warn('移除服务端附件失败（已从输入框去掉）:', error);
        }
    }

    clearPendingAttachments(deleteRemote) {
        const ids = this.pendingAttachments.map(item => item.id);
        this.pendingAttachments = [];
        this.renderPendingAttachments();
        if (!deleteRemote || !ids.length) {
            return;
        }
        ids.forEach(id => {
            fetch(
                `${this.apiBaseUrl}/chat/attachments/${encodeURIComponent(id)}?sessionId=${encodeURIComponent(this.sessionId)}`,
                { method: 'DELETE' }
            ).catch(() => {});
        });
    }

    // 格式化文件大小
    formatFileSize(bytes) {
        if (bytes === 0) return '0 Bytes';
        const k = 1024;
        const sizes = ['Bytes', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return Math.round(bytes / Math.pow(k, i) * 100) / 100 + ' ' + sizes[i];
    }

    // 发送智能运维请求（SSE 流式模式）
    async sendAIOpsRequest(loadingMessageElement) {
        try {
            const response = await fetch(`${this.apiBaseUrl}/ai_ops`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({
                    Provider: this.currentProvider
                })
            });

            if (!response.ok) {
                throw new Error(`HTTP错误: ${response.status}`);
            }

            await this.consumeAiOpsSse(response, loadingMessageElement);
        } catch (error) {
            throw error;
        }
    }

    async consumeAiOpsSse(response, loadingMessageElement) {
        let fullResponse = '';
        const stages = [];
        let pendingApproval = null;
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        let currentEvent = 'message';

        const handle = (sseMessage) => {
            if (!sseMessage || !sseMessage.type) {
                return false;
            }
            if (sseMessage.type === 'content') {
                fullResponse += sseMessage.data || '';
                if (loadingMessageElement) {
                    this.updateAIOpsStreamContent(loadingMessageElement, fullResponse);
                }
                return false;
            }
            if (sseMessage.type === 'stage') {
                const payload = this.parseJsonData(sseMessage.data);
                if (payload && payload.message) {
                    stages.push(payload.message);
                    if (loadingMessageElement) {
                        this.updateAIOpsStreamContent(loadingMessageElement, fullResponse);
                    }
                }
                return false;
            }
            if (sseMessage.type === 'approval') {
                pendingApproval = this.parseJsonData(sseMessage.data);
                return false;
            }
            if (sseMessage.type === 'incident') {
                return false;
            }
            if (sseMessage.type === 'done') {
                this.finishAiOpsMessage(loadingMessageElement, fullResponse, stages, pendingApproval);
                return true;
            }
            if (sseMessage.type === 'error') {
                throw new Error(sseMessage.data || '智能运维分析失败');
            }
            return false;
        };

        try {
            while (true) {
                const { done, value } = await reader.read();
                if (done) {
                    break;
                }
                buffer += decoder.decode(value, { stream: true });
                const lines = buffer.split('\n');
                buffer = lines.pop() || '';
                for (const line of lines) {
                    if (line.trim() === '' || line.startsWith('id:')) {
                        continue;
                    }
                    if (line.startsWith('event:')) {
                        currentEvent = line.substring(6).trim();
                        continue;
                    }
                    if (!line.startsWith('data:')) {
                        continue;
                    }
                    const rawData = line.substring(5).trim();
                    try {
                        const sseMessage = JSON.parse(rawData);
                        if (handle(sseMessage)) {
                            return;
                        }
                    } catch (e) {
                        if (e.message.includes('智能运维')) {
                            throw e;
                        }
                        fullResponse += rawData;
                        if (loadingMessageElement) {
                            this.updateAIOpsStreamContent(loadingMessageElement, fullResponse);
                        }
                    }
                }
            }
            this.finishAiOpsMessage(loadingMessageElement, fullResponse, stages, pendingApproval);
        } finally {
            reader.releaseLock();
        }
    }

    parseJsonData(data) {
        if (!data) {
            return null;
        }
        if (typeof data === 'object') {
            return data;
        }
        try {
            return JSON.parse(data);
        } catch (e) {
            return null;
        }
    }

    finishAiOpsMessage(loadingMessageElement, fullResponse, stages, pendingApproval) {
        const el = this.updateAIOpsMessage(loadingMessageElement, fullResponse, stages || []);
        if (pendingApproval && el) {
            this.renderApprovalCard(el, pendingApproval);
        }
    }

    renderApprovalCard(messageElement, payload) {
        const wrapper = messageElement.querySelector('.message-content-wrapper');
        if (!wrapper) {
            return;
        }
        const plan = payload.plan || {};
        const params = plan.params && typeof plan.params === 'object'
            ? Object.entries(plan.params).map(([k, v]) => `${k}=${v}`).join('，')
            : '';
        const card = document.createElement('div');
        card.className = 'approval-card';
        card.innerHTML = `
            <div class="approval-card-title">待审批自愈 · ${this.escapeHtml(payload.riskLevel || 'L2')}（风险分 ${this.escapeHtml(String(payload.score ?? ''))}）</div>
            <div class="approval-card-body">
                <p><strong>${this.escapeHtml(plan.title || plan.playbookId || '')}</strong> → ${this.escapeHtml(plan.target || '')}</p>
                ${params ? `<p>参数：${this.escapeHtml(params)}</p>` : ''}
                <pre class="approval-commands">${this.escapeHtml((plan.commands || []).join('\n'))}</pre>
                <p>回滚：${this.escapeHtml(plan.rollback || '-')}</p>
                <p>预期：${this.escapeHtml(plan.expectedEffect || '-')}</p>
                ${plan.decisionReason ? `<p>决策：${this.escapeHtml(plan.decisionReason)}</p>` : ''}
                ${plan.humanSuggestion ? `<p>上次建议：${this.escapeHtml(plan.humanSuggestion)}</p>` : ''}
                <p class="approval-reasons">${this.escapeHtml((payload.reasons || []).join('；'))}</p>
            </div>
            <div class="approval-actions">
                <button type="button" class="approval-btn approve">同意执行</button>
                <button type="button" class="approval-btn reject">拒绝执行</button>
                <button type="button" class="approval-btn suggest">其他建议</button>
            </div>
            <div class="approval-suggest" hidden>
                <textarea class="approval-suggest-input" rows="3" placeholder="说明希望改选哪条命令或更换哪些参数，例如：改用订单表 user_id 索引 / 把 max_connections 调到 800"></textarea>
                <button type="button" class="approval-btn approve suggest-submit">提交建议并重选</button>
            </div>
        `;
        wrapper.appendChild(card);
        card.querySelector('.approve').addEventListener('click', () => this.approveIncident(payload.incidentId, card, messageElement));
        card.querySelector('.reject').addEventListener('click', () => this.rejectIncident(payload.incidentId, card));
        card.querySelector('.suggest').addEventListener('click', () => {
            const box = card.querySelector('.approval-suggest');
            box.hidden = !box.hidden;
        });
        card.querySelector('.suggest-submit').addEventListener('click', () => {
            const text = (card.querySelector('.approval-suggest-input').value || '').trim();
            if (!text) {
                this.showNotification('请先输入其他建议', 'warning');
                return;
            }
            this.reviseIncident(payload.incidentId, text, card, messageElement);
        });
    }

    async approveIncident(incidentId, card, messageElement) {
        if (this.isStreaming) {
            this.showNotification('请等待当前操作完成', 'warning');
            return;
        }
        this.isStreaming = true;
        this.updateUI();
        try {
            card.querySelectorAll('button').forEach(btn => { btn.disabled = true; });
            const response = await fetch(`${this.apiBaseUrl}/incidents/${encodeURIComponent(incidentId)}/approve`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ approver: 'ui', comment: '前端批准' })
            });
            if (!response.ok) {
                throw new Error(`HTTP错误: ${response.status}`);
            }
            card.classList.add('approval-done');
            card.querySelector('.approval-card-title').textContent = '已同意，正在执行…';
            await this.consumeAiOpsSse(response, messageElement);
        } catch (error) {
            this.showNotification('批准执行失败：' + error.message, 'error');
            card.querySelectorAll('button').forEach(btn => { btn.disabled = false; });
        } finally {
            this.isStreaming = false;
            this.updateUI();
        }
    }

    async reviseIncident(incidentId, suggestion, card, messageElement) {
        if (this.isStreaming) {
            this.showNotification('请等待当前操作完成', 'warning');
            return;
        }
        this.isStreaming = true;
        this.updateUI();
        try {
            card.querySelectorAll('button').forEach(btn => { btn.disabled = true; });
            const response = await fetch(`${this.apiBaseUrl}/incidents/${encodeURIComponent(incidentId)}/revise`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ approver: 'ui', suggestion })
            });
            if (!response.ok) {
                throw new Error(`HTTP错误: ${response.status}`);
            }
            card.classList.add('approval-revised');
            card.querySelector('.approval-card-title').textContent = '已提交建议，正在重选命令…';
            await this.consumeAiOpsSse(response, messageElement);
        } catch (error) {
            this.showNotification('按建议重选失败：' + error.message, 'error');
            card.querySelectorAll('button').forEach(btn => { btn.disabled = false; });
        } finally {
            this.isStreaming = false;
            this.updateUI();
        }
    }

    async rejectIncident(incidentId, card) {
        try {
            const response = await fetch(`${this.apiBaseUrl}/incidents/${encodeURIComponent(incidentId)}/reject`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ approver: 'ui', comment: '前端拒绝' })
            });
            const body = await response.json();
            if (body.code !== 200) {
                throw new Error(body.message || '拒绝失败');
            }
            card.classList.add('approval-rejected');
            card.querySelectorAll('button').forEach(btn => { btn.disabled = true; });
            card.querySelector('.approval-card-title').textContent = '已拒绝执行';
        } catch (error) {
            this.showNotification('拒绝失败：' + error.message, 'error');
        }
    }

    // 更新智能运维流式内容（实时显示）
    updateAIOpsStreamContent(messageElement, content) {
        if (!messageElement) return;
        
        // 添加 aiops-message 类
        messageElement.classList.add('aiops-message');
        
        const messageContentWrapper = messageElement.querySelector('.message-content-wrapper');
        if (messageContentWrapper) {
            let messageContent = messageContentWrapper.querySelector('.message-content');
            if (!messageContent) {
                messageContent = document.createElement('div');
                messageContent.className = 'message-content';
                messageContentWrapper.appendChild(messageContent);
            }
            // 流式显示时使用纯文本
            messageContent.textContent = content;
            this.scrollToBottom();
        }
    }

    // 更新智能运维消息（带折叠详情）
    updateAIOpsMessage(messageElement, response, details) {
        console.log('updateAIOpsMessage 被调用');
        console.log('messageElement:', messageElement);
        console.log('response:', response);
        console.log('response length:', response ? response.length : 0);
        console.log('details:', details);
        
        if (!messageElement) {
            // 如果没有传入消息元素，则创建新消息
            console.log('messageElement 为空，创建新消息');
            return this.addAIOpsMessage(response, details);
        }

        // 添加aiops-message类
        messageElement.classList.add('aiops-message');

        // 获取消息内容包装器
        const messageContentWrapper = messageElement.querySelector('.message-content-wrapper');
        if (!messageContentWrapper) {
            console.error('未找到 message-content-wrapper');
            return;
        }

        // 清空现有内容（保留消息内容容器）
        const messageContent = messageContentWrapper.querySelector('.message-content');
        if (!messageContent) {
            console.error('未找到 message-content');
            return;
        }

        // 移除加载动画相关的类和内容
        messageContent.classList.remove('loading-message-content');
        messageContent.textContent = '';
        
        // 移除加载图标（如果存在）
        const loadingIcon = messageContent.querySelector('.loading-spinner-icon');
        if (loadingIcon) {
            loadingIcon.remove();
        }

        // 详情部分（可折叠）- 先显示
        if (details && details.length > 0) {
            // 检查是否已存在详情容器
            let detailsContainer = messageElement.querySelector('.aiops-details');
            if (!detailsContainer) {
                detailsContainer = document.createElement('div');
                detailsContainer.className = 'aiops-details';
                messageContentWrapper.insertBefore(detailsContainer, messageContent);
            } else {
                // 清空现有详情
                detailsContainer.innerHTML = '';
            }

            const detailsToggle = document.createElement('div');
            detailsToggle.className = 'details-toggle';
            detailsToggle.innerHTML = `
                <svg class="toggle-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path d="M9 18L15 12L9 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
                <span>查看详细步骤 (${details.length}条)</span>
            `;

            const detailsContent = document.createElement('div');
            detailsContent.className = 'details-content';
            
            details.forEach((detail, index) => {
                const detailItem = document.createElement('div');
                detailItem.className = 'detail-item';
                detailItem.innerHTML = `<strong>步骤 ${index + 1}:</strong> ${this.escapeHtml(detail)}`;
                detailsContent.appendChild(detailItem);
            });

            // 点击切换折叠状态
            detailsToggle.addEventListener('click', () => {
                detailsContent.classList.toggle('expanded');
                detailsToggle.classList.toggle('expanded');
            });

            detailsContainer.appendChild(detailsToggle);
            detailsContainer.appendChild(detailsContent);
        }

        // 更新主要响应内容（使用Markdown渲染）
        console.log('开始渲染 Markdown');
        const renderedHtml = this.renderMarkdown(response);
        console.log('Markdown 渲染完成，HTML 长度:', renderedHtml ? renderedHtml.length : 0);
        messageContent.innerHTML = renderedHtml;
        console.log('innerHTML 已设置');
        // 高亮代码块
        this.highlightCodeBlocks(messageContent);
        console.log('代码块高亮完成');
        
        // 保存到历史记录
        this.currentChatHistory.push({
            type: 'assistant',
            content: response,
            timestamp: new Date().toISOString()
        });
        
        this.scrollToBottom();
        return messageElement;
    }

    // 添加智能运维消息（带折叠详情）- 保留用于兼容性
    addAIOpsMessage(response, details) {
        const messageDiv = document.createElement('div');
        messageDiv.className = 'message assistant aiops-message';

        // 添加头像图标
        const messageAvatar = document.createElement('div');
        messageAvatar.className = 'message-avatar';
        messageAvatar.innerHTML = `
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M12 2L15.09 8.26L22 9.27L17 14.14L18.18 21.02L12 17.77L5.82 21.02L7 14.14L2 9.27L8.91 8.26L12 2Z" fill="white"/>
            </svg>
        `;
        messageDiv.appendChild(messageAvatar);

        // 创建消息内容包装器
        const messageContentWrapper = document.createElement('div');
        messageContentWrapper.className = 'message-content-wrapper';

        // 详情部分（可折叠）- 先显示
        if (details && details.length > 0) {
            const detailsContainer = document.createElement('div');
            detailsContainer.className = 'aiops-details';

            const detailsToggle = document.createElement('div');
            detailsToggle.className = 'details-toggle';
            detailsToggle.innerHTML = `
                <svg class="toggle-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path d="M9 18L15 12L9 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
                <span>查看详细步骤 (${details.length}条)</span>
            `;

            const detailsContent = document.createElement('div');
            detailsContent.className = 'details-content';
            
            details.forEach((detail, index) => {
                const detailItem = document.createElement('div');
                detailItem.className = 'detail-item';
                detailItem.innerHTML = `<strong>步骤 ${index + 1}:</strong> ${this.escapeHtml(detail)}`;
                detailsContent.appendChild(detailItem);
            });

            // 点击切换折叠状态
            detailsToggle.addEventListener('click', () => {
                detailsContent.classList.toggle('expanded');
                detailsToggle.classList.toggle('expanded');
            });

            detailsContainer.appendChild(detailsToggle);
            detailsContainer.appendChild(detailsContent);
            messageContentWrapper.appendChild(detailsContainer);
        }

        // 主要响应内容 - 后显示（使用Markdown渲染）
        const messageContent = document.createElement('div');
        messageContent.className = 'message-content';
        messageContent.innerHTML = this.renderMarkdown(response);
        // 高亮代码块
        this.highlightCodeBlocks(messageContent);
        messageContentWrapper.appendChild(messageContent);
        messageDiv.appendChild(messageContentWrapper);
        
        if (this.chatMessages) {
            this.chatMessages.appendChild(messageDiv);
            this.scrollToBottom();
        }

        return messageDiv;
    }

    // HTML转义
    escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    // 触发智能运维（点击智能运维按钮时直接调用）
    async triggerAIOps() {
        if (this.isStreaming) {
            this.showNotification('请等待当前操作完成', 'warning');
            return;
        }

        // 新建对话
        this.newChat();
        
        // 添加"分析中..."的消息（带旋转动画）
        const loadingMessage = this.addLoadingMessage('分析中...');
        this.currentAIOpsMessage = loadingMessage; // 保存消息引用用于后续更新
        
        // 设置发送状态
        this.isStreaming = true;
        this.updateUI();

        try {
            await this.sendAIOpsRequest(loadingMessage);
        } catch (error) {
            console.error('智能运维分析失败:', error);
            // 更新消息为错误信息
            if (loadingMessage) {
                const messageContent = loadingMessage.querySelector('.message-content');
                if (messageContent) {
                    messageContent.textContent = '抱歉，智能运维分析时出现错误：' + error.message;
                }
            }
        } finally {
            this.isStreaming = false;
            this.currentAIOpsMessage = null;
            this.updateUI();
        }
    }

    // 显示/隐藏加载遮罩层
    showLoadingOverlay(show) {
        if (this.loadingOverlay) {
            if (show) {
                this.loadingOverlay.style.display = 'flex';
                // 更新文字为智能运维
                const loadingText = this.loadingOverlay.querySelector('.loading-text');
                const loadingSubtext = this.loadingOverlay.querySelector('.loading-subtext');
                if (loadingText) loadingText.textContent = '智能运维分析中，请稍候...';
                if (loadingSubtext) loadingSubtext.textContent = '后端正在处理，请耐心等待';
                // 防止页面滚动
                document.body.style.overflow = 'hidden';
            } else {
                this.loadingOverlay.style.display = 'none';
                // 恢复页面滚动
                document.body.style.overflow = '';
            }
        }
    }

    // 显示/隐藏上传遮罩层
    showUploadOverlay(show, fileName = '') {
        if (this.loadingOverlay) {
            if (show) {
                this.loadingOverlay.style.display = 'flex';
                // 更新文字为上传中
                const loadingText = this.loadingOverlay.querySelector('.loading-text');
                const loadingSubtext = this.loadingOverlay.querySelector('.loading-subtext');
                if (loadingText) loadingText.textContent = '正在上传文件...';
                if (loadingSubtext) loadingSubtext.textContent = fileName ? `上传: ${fileName}` : '请稍候';
                // 防止页面滚动
                document.body.style.overflow = 'hidden';
            } else {
                this.loadingOverlay.style.display = 'none';
                // 恢复页面滚动
                document.body.style.overflow = '';
            }
        }
    }
}

// 添加CSS动画
const style = document.createElement('style');
style.textContent = `
    @keyframes slideIn {
        from {
            transform: translateX(100%);
            opacity: 0;
        }
        to {
            transform: translateX(0);
            opacity: 1;
        }
    }
    
    @keyframes slideOut {
        from {
            transform: translateX(0);
            opacity: 1;
        }
        to {
            transform: translateX(100%);
            opacity: 0;
        }
    }
`;
document.head.appendChild(style);

// 初始化应用
document.addEventListener('DOMContentLoaded', () => {
    new SuperBizAgentApp();
});
