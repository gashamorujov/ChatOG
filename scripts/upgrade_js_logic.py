import re

with open('app/src/main/assets/index.html', 'r', encoding='utf-8') as f:
    code = f.read()

# 1. Replace read receipts with professional SVG checks
old_receipt_logic = """                            if (isMe) {
                                const readReceipt = msg.read ? '🟩' : '🟥';
                                timeDiv.innerHTML = `${utils.formatTimestamp(msg.timestamp)} <span class="read-receipt">${readReceipt}</span>`;
                            } else {
                                timeDiv.innerText = utils.formatTimestamp(msg.timestamp);
                            }"""

new_receipt_logic = """                            if (isMe) {
                                const checkSvg = msg.read
                                    ? `<svg class="receipt-icon read" viewBox="0 0 20 16" fill="currentColor"><path d="M11.854 3.646a.5.5 0 0 1 0 .708l-7 7a.5.5 0 0 1-.708 0l-3.5-3.5a.5.5 0 1 1 .708-.708L4.5 10.293l6.646-6.647a.5.5 0 0 1 .708 0z"/><path d="M16.854 3.646a.5.5 0 0 1 0 .708l-7 7a.5.5 0 0 1-.708 0l-.5-.5a.5.5 0 1 1 .708-.708l.146.147 6.646-6.647a.5.5 0 0 1 .708 0z"/></svg>`
                                    : `<svg class="receipt-icon" viewBox="0 0 16 16" fill="currentColor"><path d="M13.854 3.646a.5.5 0 0 1 0 .708l-7 7a.5.5 0 0 1-.708 0l-3.5-3.5a.5.5 0 1 1 .708-.708L6.5 10.293l6.646-6.647a.5.5 0 0 1 .708 0z"/></svg>`;
                                timeDiv.innerHTML = `${utils.formatTimestamp(msg.timestamp)} <span class="read-receipt">${checkSvg}</span>`;
                            } else {
                                timeDiv.innerText = utils.formatTimestamp(msg.timestamp);
                            }"""

if old_receipt_logic in code:
    code = code.replace(old_receipt_logic, new_receipt_logic)
    print("Replaced text message read receipt logic")

# In createMediaMessageHTML, createFileMessageHTML, createAudioMessageHTML, createVoiceMessageHTML:
code = code.replace("const readReceipt = isMe ? (msg.read ? '🟩' : '🟥') : '';",
                    """const readReceipt = isMe ? (msg.read 
                        ? `<svg class="receipt-icon read" viewBox="0 0 20 16" fill="currentColor"><path d="M11.854 3.646a.5.5 0 0 1 0 .708l-7 7a.5.5 0 0 1-.708 0l-3.5-3.5a.5.5 0 1 1 .708-.708L4.5 10.293l6.646-6.647a.5.5 0 0 1 .708 0z"/><path d="M16.854 3.646a.5.5 0 0 1 0 .708l-7 7a.5.5 0 0 1-.708 0l-.5-.5a.5.5 0 1 1 .708-.708l.146.147 6.646-6.647a.5.5 0 0 1 .708 0z"/></svg>`
                        : `<svg class="receipt-icon" viewBox="0 0 16 16" fill="currentColor"><path d="M13.854 3.646a.5.5 0 0 1 0 .708l-7 7a.5.5 0 0 1-.708 0l-3.5-3.5a.5.5 0 1 1 .708-.708L6.5 10.293l6.646-6.647a.5.5 0 0 1 .708 0z"/></svg>`) : '';""")

# Replace download icon
code = code.replace('>⬇️</button>', '><svg class="app-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg></button>')

# Replace play button icon
code = code.replace('>▶️</button>', '><svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg></button>')

# Replace play icon state updates
code = code.replace(".innerText = '▶️';", ".innerHTML = '<svg viewBox=\"0 0 24 24\" width=\"18\" height=\"18\" fill=\"currentColor\"><polygon points=\"5 3 19 12 5 21 5 3\"/></svg>';")
code = code.replace(".innerText = '⏸';", ".innerHTML = '<svg viewBox=\"0 0 24 24\" width=\"18\" height=\"18\" fill=\"currentColor\"><rect x=\"6\" y=\"4\" width=\"4\" height=\"16\"/><rect x=\"14\" y=\"4\" width=\"4\" height=\"16\"/></svg>';")

# Replace request buttons in listenRequests:
old_req_btns = """                    <button class="icon-btn" style="color:var(--success)" onclick="window.acceptReq('${key}','${req.from}')">✓</button>
                    <button class="icon-btn" style="color:var(--danger)" onclick="window.rejectReq('${key}')">✗</button>"""

new_req_btns = """                    <button class="icon-btn" style="color:var(--success); padding: 8px;" onclick="window.acceptReq('${key}','${req.from}')" title="Qəbul et" aria-label="Qəbul et">
                        <svg class="app-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="20 6 9 17 4 12"/></svg>
                    </button>
                    <button class="icon-btn" style="color:var(--danger); padding: 8px;" onclick="window.rejectReq('${key}')" title="Rədd et" aria-label="Rədd et">
                        <svg class="app-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                    </button>"""

if old_req_btns in code:
    code = code.replace(old_req_btns, new_req_btns)
    print("Replaced request buttons")

# Pinned message icons
code = code.replace('<span class="pinned-msg-icon">📌</span>', '<span class="pinned-msg-icon"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="17" x2="12" y2="22"/><path d="M5 17h14v-2l-2-2V5a2 2 0 0 0-2-2h-6a2 2 0 0 0-2 2v8l-2 2v2z"/></svg></span>')
code = code.replace('title="Unpin">✖</button>', 'title="Unpin"><svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg></button>')

# Call log delete button icon
code = code.replace('title="Sil">🗑️</button>', 'title="Sil"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/></svg></button>')

# Call log icons
code = code.replace("const icon = log.type === 'video' ? '📹' : '📞';",
                    "const icon = log.type === 'video' ? '<svg viewBox=\"0 0 24 24\" width=\"24\" height=\"24\" fill=\"none\" stroke=\"var(--accent-blue)\" stroke-width=\"2\"><polygon points=\"23 7 16 12 23 17 23 7\"/><rect x=\"1\" y=\"5\" width=\"15\" height=\"14\" rx=\"2\" ry=\"2\"/></svg>' : '<svg viewBox=\"0 0 24 24\" width=\"24\" height=\"24\" fill=\"none\" stroke=\"var(--primary-color)\" stroke-width=\"2\"><path d=\"M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z\"/></svg>';")

code = code.replace("const directionIcon = log.direction === 'incoming' ? '📥' : '📤';",
                    "const directionIcon = log.direction === 'incoming' ? '<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"var(--primary-color)\" stroke-width=\"2\" style=\"vertical-align:-2px; margin-right:4px;\"><line x1=\"12\" y1=\"5\" x2=\"12\" y2=\"19\"/><polyline points=\"19 12 12 19 5 12\"/></svg>' : '<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"var(--accent-blue)\" stroke-width=\"2\" style=\"vertical-align:-2px; margin-right:4px;\"><line x1=\"12\" y1=\"19\" x2=\"12\" y2=\"5\"/><polyline points=\"5 12 12 5 19 12\"/></svg>';")

# Status header buttons in listenStories:
old_stories_header = """            <div style="display: flex; gap: 10px; margin-bottom: 20px; padding: 0 5px;">
                <button id="btn-add-story-section" style="flex: 1; padding: 12px; background: linear-gradient(135deg, var(--secondary-color), #FFA500); border-radius: 12px; border: none; color: #0A0E27; font-weight: 700; cursor: pointer;">
                    ➕ Story Əlavə Et
                </button>
                <button id="btn-manage-stories" style="flex: 1; padding: 12px; background: linear-gradient(135deg, var(--accent-blue), #0080FF); border-radius: 12px; border: none; color: white; font-weight: 700; cursor: pointer;">
                    ⚙️ Storiləri İdarə Et
                </button>
            </div>"""

new_stories_header = """            <div style="display: flex; gap: 10px; margin-bottom: 20px; padding: 0 5px;">
                <button id="btn-add-story-section" style="flex: 1; padding: 12px; background: linear-gradient(135deg, var(--secondary-color), #FFA500); border-radius: 12px; border: none; color: #0A0E27; font-weight: 700; cursor: pointer; display: flex; align-items: center; justify-content: center; gap: 6px;">
                    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.5"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="16"/><line x1="8" y1="12" x2="16" y2="12"/></svg>
                    Story Paylaş
                </button>
                <button id="btn-manage-stories" style="flex: 1; padding: 12px; background: linear-gradient(135deg, var(--accent-blue), #0080FF); border-radius: 12px; border: none; color: white; font-weight: 700; cursor: pointer; display: flex; align-items: center; justify-content: center; gap: 6px;">
                    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
                    İdarə Et
                </button>
            </div>"""

if old_stories_header in code:
    code = code.replace(old_stories_header, new_stories_header)
    print("Replaced stories header buttons")

# My story view icon in listenStories:
code = code.replace('<button class="icon-btn" style="color: var(--secondary-color); font-size: 1.5rem;">👁️</button>',
                    '<button class="icon-btn" style="color: var(--secondary-color);"><svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg></button>')

# Manage stories delete button:
code = code.replace('title="Sil">🗑️</button>',
                    'title="Sil"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/></svg></button>')

# Fix status upload logic in JS
old_story_upload_handlers = """    document.getElementById('btn-story-photo').onclick = () => {
        state.currentStoryType = 'image';
        document.getElementById('story-file-input').accept = 'image/*';
        document.getElementById('story-file-input').click();
    };

    document.getElementById('btn-story-video').onclick = () => {
        state.currentStoryType = 'video';
        document.getElementById('story-file-input').accept = 'video/*';
        document.getElementById('story-file-input').click();
    };

    document.getElementById('story-file-input').onchange = async (e) => {
        const file = e.target.files[0];
        if (!file) return;

        const isImage = file.type.startsWith('image/');
        const isVideo = file.type.startsWith('video/');

        if (!isImage && !isVideo) {
            alert('Yalnız şəkil və ya video!');
            return;
        }

        const maxSize = isImage ? 10 * 1024 * 1024 : 50 * 1024 * 1024;
        if (file.size > maxSize) {
            alert(`Fayl çox böyükdür! Max: ${isImage ? '10MB' : '50MB'}`);
            return;
        }

        ui.showLoading();
        try {
            let fileData;
            if (isImage) {
                const compressed = await fileHandler.compressImage(file, 800, 0.7);
                fileData = await fileHandler.blobToBase64(compressed);
            } else {
                fileData = await fileHandler.toBase64(file);
            }

            const storyData = {
                type: isImage ? 'image' : 'video',
                mediaUrl: fileData,
                timestamp: Date.now(),
                userName: state.currentUser.name,
                userDp: state.currentUser.dpUrl
            };

            await push(ref(db, `stories/${state.currentUser.uid}`), storyData);
            document.getElementById('story-create-modal').style.display = 'none';
            ui.toast('✅ Story paylaşıldı!');
        } catch (e) {
            console.error(e);
            alert('Story yükləmə xətası!');
        }
        ui.hideLoading();
        e.target.value = '';
    };"""

new_story_upload_handlers = """    document.getElementById('btn-story-photo').onclick = () => {
        const input = document.getElementById('story-photo-input');
        if (input) input.click();
    };

    document.getElementById('btn-story-video').onclick = () => {
        const input = document.getElementById('story-video-input');
        if (input) input.click();
    };

    const photoInput = document.getElementById('story-photo-input');
    if (photoInput) {
        photoInput.onchange = async (e) => {
            const file = e.target.files[0];
            if (!file) return;

            if (!file.type.startsWith('image/')) {
                alert('Zəhmət olmasa şəkil faylı seçin!');
                e.target.value = '';
                return;
            }

            if (file.size > 12 * 1024 * 1024) {
                alert('Şəkil 12MB-dan böyük ola bilməz!');
                e.target.value = '';
                return;
            }

            ui.showLoading();
            try {
                const compressed = await fileHandler.compressImage(file, 900, 0.75);
                const fileData = await fileHandler.blobToBase64(compressed);
                const storyData = {
                    type: 'image',
                    mediaUrl: fileData,
                    timestamp: Date.now(),
                    userName: (state.currentUser && state.currentUser.name) || 'User',
                    userDp: (state.currentUser && state.currentUser.dpUrl) || ''
                };
                await push(ref(db, `stories/${state.currentUser.uid}`), storyData);
                document.getElementById('story-create-modal').style.display = 'none';
                ui.toast('Şəkil statusu paylaşıldı!');
            } catch (err) {
                console.error('Story photo error:', err);
                alert('Şəkil yüklənərkən xəta baş verdi!');
            } finally {
                ui.hideLoading();
                e.target.value = '';
            }
        };
    }

    const videoInput = document.getElementById('story-video-input');
    if (videoInput) {
        videoInput.onchange = async (e) => {
            const file = e.target.files[0];
            if (!file) return;

            if (!file.type.startsWith('video/')) {
                alert('Zəhmət olmasa video faylı seçin!');
                e.target.value = '';
                return;
            }

            // Safe 12MB limit to prevent WebView OutOfMemory and WebSocket disconnection
            if (file.size > 12 * 1024 * 1024) {
                alert('Video faylı 12MB-dan böyük ola bilməz! Zəhmət olmasa daha qısa video seçin.');
                e.target.value = '';
                return;
            }

            ui.showLoading();
            try {
                const fileData = await fileHandler.toBase64(file);
                const storyData = {
                    type: 'video',
                    mediaUrl: fileData,
                    timestamp: Date.now(),
                    userName: (state.currentUser && state.currentUser.name) || 'User',
                    userDp: (state.currentUser && state.currentUser.dpUrl) || ''
                };
                await push(ref(db, `stories/${state.currentUser.uid}`), storyData);
                document.getElementById('story-create-modal').style.display = 'none';
                ui.toast('Video statusu paylaşıldı!');
            } catch (err) {
                console.error('Story video error:', err);
                alert('Video yüklənərkən xəta baş verdi: ' + (err.message || ''));
            } finally {
                ui.hideLoading();
                e.target.value = '';
            }
        };
    }"""

if old_story_upload_handlers in code:
    code = code.replace(old_story_upload_handlers, new_story_upload_handlers)
    print("Replaced story upload handlers with crash-proof logic")
else:
    print("Warning: old_story_upload_handlers not found exactly, searching substring")

# Replace showStoryAtIndex with safe video handling
old_show_story = """        if (story.type === 'image') {
            imgEl.src = story.mediaUrl;
            imgEl.style.display = 'block';
            videoEl.style.display = 'none';
            videoEl.pause();
            startStoryProgress(index, 5000, () => showStoryAtIndex(index + 1));
        } else {
            videoEl.src = story.mediaUrl;
            videoEl.style.display = 'block';
            imgEl.style.display = 'none';
            videoEl.play().catch(e => console.log('Play error:', e));
            
            videoEl.onloadedmetadata = () => {
                const duration = videoEl.duration * 1000;
                startStoryProgress(index, duration, () => showStoryAtIndex(index + 1));
            };
            
            videoEl.onended = () => {
                showStoryAtIndex(index + 1);
            };
        }"""

new_show_story = """        if (story.type === 'image') {
            videoEl.pause();
            videoEl.style.display = 'none';
            videoEl.removeAttribute('src');

            imgEl.onload = () => {
                startStoryProgress(index, 5000, () => showStoryAtIndex(index + 1));
            };
            imgEl.onerror = () => {
                console.warn('Image story failed to load');
                startStoryProgress(index, 3000, () => showStoryAtIndex(index + 1));
            };
            imgEl.src = story.mediaUrl || '';
            imgEl.style.display = 'block';
        } else {
            imgEl.style.display = 'none';
            imgEl.src = '';

            videoEl.style.display = 'block';
            videoEl.onerror = () => {
                console.warn('Video story playback error');
                ui.toast('Video oynadılarkən xəta baş verdi');
                startStoryProgress(index, 3000, () => showStoryAtIndex(index + 1));
            };

            let durationStarted = false;
            videoEl.onloadedmetadata = () => {
                if (durationStarted) return;
                durationStarted = true;
                const dur = isFinite(videoEl.duration) && videoEl.duration > 0 ? videoEl.duration * 1000 : 6000;
                startStoryProgress(index, Math.min(dur, 30000), () => showStoryAtIndex(index + 1));
            };

            setTimeout(() => {
                if (!durationStarted && state.currentStoryIndex === index) {
                    durationStarted = true;
                    startStoryProgress(index, 7000, () => showStoryAtIndex(index + 1));
                }
            }, 2500);

            videoEl.onended = () => {
                showStoryAtIndex(index + 1);
            };

            videoEl.src = story.mediaUrl || '';
            videoEl.play().catch(e => console.log('Video play error handled:', e));
        }"""

if old_show_story in code:
    code = code.replace(old_show_story, new_show_story)
    print("Replaced showStoryAtIndex video handler")

# Update closeStoryView to clean up video hardware decoder
old_close_story = """    function closeStoryView() {
        if (state.storyProgressInterval) {
            clearInterval(state.storyProgressInterval);
        }
        
        const videoEl = document.getElementById('story-video');
        if (videoEl) {
            videoEl.pause();
            videoEl.src = '';
        }
        
        document.getElementById('story-view-modal').style.display = 'none';
        document.getElementById('story-media').src = '';
        
        state.currentStoriesArray = [];
        state.currentStoryIndex = 0;
        state.currentStoryUserId = null;
        state.currentStoryUserData = null;
    }"""

new_close_story = """    function closeStoryView() {
        if (state.storyProgressInterval) {
            clearInterval(state.storyProgressInterval);
            state.storyProgressInterval = null;
        }
        
        const videoEl = document.getElementById('story-video');
        if (videoEl) {
            videoEl.pause();
            videoEl.removeAttribute('src');
            videoEl.load();
            videoEl.style.display = 'none';
        }
        
        const imgEl = document.getElementById('story-media');
        if (imgEl) {
            imgEl.removeAttribute('src');
            imgEl.style.display = 'none';
        }
        
        document.getElementById('story-view-modal').style.display = 'none';
        
        state.currentStoriesArray = [];
        state.currentStoryIndex = 0;
        state.currentStoryUserId = null;
        state.currentStoryUserData = null;
    }"""

if old_close_story in code:
    code = code.replace(old_close_story, new_close_story)
    print("Replaced closeStoryView")

with open('app/src/main/assets/index.html', 'w', encoding='utf-8') as f:
    f.write(code)

print("Step 2 complete")
