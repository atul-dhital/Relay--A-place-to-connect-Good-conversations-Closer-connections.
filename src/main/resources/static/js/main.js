'use strict';
var $ = function(id) { return document.querySelector('#' + id); };
var username = $('username').textContent.trim();
var csrfToken = document.querySelector('meta[name="csrf-token"]').content;
var stompClient, currentRoomId = null, activeKey = 'public', reconnectTimer, attempts = 0, stopping = false;
var sending = false, pending = null, pendingTimer, previewUrl, roomPending = false, roomTimer, connected = false, sendGeneration = 0;
var conversations = new Map();
function conversation(key, type, name, target) {
    if (!conversations.has(key)) conversations.set(key, { key: key, type: type, name: name, target: target, messages: [], members: [], unread: 0, draft: '', file: null, joined: false });
    return conversations.get(key);
}
conversation('public', 'public', 'Public lounge');
function active() { return conversations.get(activeKey); }
function ready() { return !!(connected && stompClient && stompClient.connected); }
function node(tag, cls, text) {
    var el = document.createElement(tag); if (cls) el.className = cls;
    if (text !== undefined) el.textContent = text; return el;
}
function avatar(el, name, type) {
    var hash = Array.from(name).reduce(function(sum, ch) { return sum + ch.charCodeAt(0); }, 0);
    el.className = 'avatar ' + (type === 'public' ? 'avatar-public' : type === 'room' ? 'avatar-room' : 'tone-' + hash % 6);
    el.textContent = type === 'direct' ? name.slice(0, 2).toUpperCase() : '#';
}
function showError(text) { $('feedback').textContent = text || ''; $('feedback').classList.toggle('hidden', !text); }
function toast(text) { $('toast').textContent = text; $('toast').hidden = false; setTimeout(function() { $('toast').hidden = true; }, 3500); }
function time(value) { return new Date(value || Date.now()).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }); }
function resize() { $('message').style.height = 'auto'; $('message').style.height = Math.min($('message').scrollHeight, 144) + 'px'; }
function controls() {
    $('sendButton').disabled = !ready() || sending || (active().type === 'room' && !active().joined) || (!$('message').value.trim() && !active().file);
    $('chat-container').classList.toggle('is-sending', sending);
    $('connectionText').textContent = ready() ? 'Connected · live conversations' : 'Connecting to chat';
    $('chat-container').classList.toggle('is-connected', ready());
    $('createRoomButton').disabled = !ready() || roomPending || sending;
    $('joinRoomButton').disabled = !ready() || roomPending;
}
function attachment() {
    if (previewUrl) URL.revokeObjectURL(previewUrl);
    previewUrl = null; var file = active().file;
    $('selectedImage').hidden = !file;
    if (file) { previewUrl = URL.createObjectURL(file); $('attachmentThumbnail').src = previewUrl; $('attachmentName').textContent = file.name; }
    else $('attachmentThumbnail').removeAttribute('src');
    controls();
}
function renderList() {
    var list = $('conversationList'); list.replaceChildren();
    var query = $('conversationSearch').value.trim().toLowerCase();
    list.appendChild(node('div', 'list-label', 'YOUR CONVERSATIONS'));
    var visible = 0;
    conversations.forEach(function(c) {
        var last = c.messages[c.messages.length - 1];
        var preview = last ? (last.content || 'Shared an image') : c.type === 'public' ? 'A place for everyone' : c.type === 'room' ? (c.joined ? 'Private room · joined' : 'Room history · rejoin to send') : 'Start a conversation';
        if (query && !(c.name + ' ' + preview).toLowerCase().includes(query)) return;
        visible++;
        var row = node('button', 'conversation-row' + (c.key === activeKey ? ' active' : ''));
        row.type = 'button'; row.setAttribute('aria-label', c.name); row.setAttribute('aria-current', String(c.key === activeKey));
        var av = node('span'); avatar(av, c.name, c.type); row.appendChild(av);
        var copy = node('span', 'conversation-copy'), top = node('span', 'conversation-row-top'), bottom = node('span', 'conversation-row-bottom');
        top.appendChild(node('span', 'conversation-name', c.name)); top.appendChild(node('span', 'conversation-time', last ? time(last.timestamp) : ''));
        bottom.appendChild(node('span', 'conversation-preview', preview));
        if (c.unread) bottom.appendChild(node('span', 'unread-badge', c.unread > 99 ? '99+' : c.unread));
        copy.appendChild(top); copy.appendChild(bottom); row.appendChild(copy);
        row.addEventListener('click', function() { select(c.key); }); list.appendChild(row);
    });
    if (!visible) list.appendChild(node('p', 'list-empty', 'No conversations found.'));
}
function updateRoomUsers(users) {
    $('roomUsersList').replaceChildren();
    (users || []).forEach(function(user) {
        var item = node('li', 'user-item'), av = node('span'); avatar(av, user, 'direct'); item.appendChild(av);
        var copy = node('span', 'user-item-copy'); copy.appendChild(node('strong', '', user));
        copy.appendChild(node('span', '', user === username ? 'You · in this room' : 'In this room')); item.appendChild(copy);
        item.appendChild(node('span', 'member-dot')); $('roomUsersList').appendChild(item);
    });
    $('memberCount').textContent = (users || []).length;
}
function renderHeader() {
    var c = active(); $('currentRoom').textContent = c.name;
    avatar($('conversationAvatar'), c.name, c.type); $('conversationAvatar').classList.add('avatar-header');
    avatar($('detailsAvatar'), c.name, c.type); $('detailsAvatar').classList.add('avatar-large');
    $('detailsTitle').textContent = c.name;
    var description = c.type === 'public' ? 'Everyone connected can join the conversation' : c.type === 'direct' ? 'Private conversation with ' + c.target : c.joined ? c.members.length + ' members · private room' : 'You have left this room';
    $('conversationSubtitle').textContent = description; $('detailsDescription').textContent = description;
    $('roomInvite').hidden = c.type !== 'room'; $('roomMembers').hidden = c.type !== 'room';
    $('inviteCode').value = c.type === 'room' ? c.target : '';
    $('leaveRoomButton').hidden = c.type !== 'room' || !c.joined;
    $('conversationAbout').textContent = c.type === 'public' ? 'Messages reach everyone currently connected. History stays in this browser tab while it is open.' : c.type === 'direct' ? 'Only you and this nickname receive these messages. Your contact must be connected to receive them.' : 'Share the room code with someone connected. Messages reach current room members.';
    $('composerContext').textContent = c.type === 'room' && !c.joined ? 'Rejoin using the room code to send messages.' : c.type === 'direct' ? 'Only you and ' + c.target : c.type === 'room' ? 'Visible to room members' : 'Visible to everyone connected';
    $('message').placeholder = c.type === 'direct' ? 'Message ' + c.target + '…' : 'Write a message…';
    updateRoomUsers(c.members); controls();
}
function renderMessages() {
    var c = active(), area = $('messageArea'); area.replaceChildren(); $('emptyState').hidden = c.messages.length > 0;
    $('emptyTitle').textContent = c.type === 'public' ? 'Make yourself at home' : 'Start the conversation';
    $('emptyDescription').textContent = c.type === 'public' ? 'Say hello, share an idea, or send a photo. Everyone connected can join in.' : 'Your messages will appear here. A simple hello is a good place to start.';
    var day;
    c.messages.forEach(function(m) {
        var date = new Date(m.timestamp || Date.now()).toLocaleDateString([], { month: 'short', day: 'numeric', year: 'numeric' });
        if (date !== day) { area.appendChild(node('li', 'date-divider', date)); day = date; }
        if (m.type === 'JOIN' || m.type === 'LEAVE') { area.appendChild(node('li', 'event-message', m.content || m.sender + (m.type === 'JOIN' ? ' joined' : ' left'))); return; }
        var own = m.sender === username, row = node('li', 'message-row' + (own ? ' own' : ''));
        if (!own) { var av = node('span'); avatar(av, m.sender || '?', 'direct'); av.classList.add('message-avatar'); row.appendChild(av); }
        var stack = node('div', 'message-stack'), bubble = node('div', 'bubble');
        if (!own && c.type !== 'direct') bubble.appendChild(node('strong', 'nickname', m.sender));
        if (m.content) bubble.appendChild(node('span', 'message-content', m.content));
        if (m.imageUrl && /^\/images\/[A-Za-z0-9_.-]+[.](png|jpg|jpeg|gif)$/.test(m.imageUrl)) {
            var img = node('img', 'message-image'); img.src = m.imageUrl; img.alt = 'Image sent by ' + m.sender;
            img.addEventListener('error', function() { img.hidden = true; bubble.appendChild(node('span', '', 'Image unavailable')); }); bubble.appendChild(img);
        }
        stack.appendChild(bubble); stack.appendChild(node('span', 'message-meta', time(m.timestamp))); row.appendChild(stack); area.appendChild(row);
    });
    var canvas = document.querySelector('.message-canvas'); canvas.scrollTo({ top: canvas.scrollHeight, behavior: 'auto' });
}
function conversationVisible(key) {
    return activeKey === key && !document.hidden && !(window.matchMedia && window.matchMedia('(max-width: 700px)').matches && !$('chat-container').classList.contains('mobile-chat-open'));
}
function select(key) {
    active().draft = $('message').value;
    activeKey = key; active().unread = 0; $('message').value = active().draft; $('imageInput').value = '';
    $('chat-container').classList.add('mobile-chat-open'); showError(''); attachment(); resize(); renderList(); renderHeader(); renderMessages();
}
function completeSend(error) {
    clearTimeout(pendingTimer); sending = false;
    if (!error && pending) {
        var c = conversations.get(pending.key);
        if (c.draft === pending.raw && c.file === pending.file) {
            c.draft = ''; c.file = null;
            if (activeKey === c.key && $('message').value === pending.raw) { $('message').value = ''; attachment(); resize(); }
        }
    }
    pending = null; if (error) showError(error); controls();
}
function receive(m, c) {
    c.messages.push(m);
    if (pending && m.sender === username && pending.key === c.key && m.content === pending.content && (m.imageUrl || null) === pending.imageUrl) completeSend();
    if (!conversationVisible(c.key) && m.sender !== username && (m.type === 'CHAT' || m.type === 'PRIVATE')) c.unread++;
    renderList(); if (activeKey === c.key) renderMessages();
    if (document.hidden && 'Notification' in window && Notification.permission === 'granted' && m.sender !== username && (m.type === 'CHAT' || m.type === 'PRIVATE')) new Notification(m.sender, { body: m.content || 'Shared an image' });
}
function onPrivateMessageReceived(payload) {
    var m = JSON.parse(payload.body);
    if (m.type === 'ERROR') {
        clearTimeout(roomTimer); roomPending = false; $('roomDialogFeedback').textContent = m.content; if (sending) completeSend(m.content); else showError(m.content); controls(); return;
    }
    if (m.type === 'ROOM_JOINED') {
        if (currentRoomId) conversation('room:' + currentRoomId).joined = false;
        currentRoomId = m.roomId;
        var c = conversation('room:' + m.roomId, 'room', 'Room ' + m.roomId.slice(0, 6), m.roomId);
        c.joined = true; c.members = m.roomUsers || []; clearTimeout(roomTimer); roomPending = false; $('roomDialog').close(); select(c.key); return;
    }
    if (m.type === 'ROOM_LEFT') {
        if (currentRoomId) conversations.get('room:' + currentRoomId).joined = false;
        currentRoomId = null; clearTimeout(roomTimer); roomPending = false;
        if (active().type === 'room') select('public'); else { renderList(); renderHeader(); } return;
    }
    var other = m.sender === username ? m.recipient : m.sender;
    receive(m, conversation('direct:' + other, 'direct', other, other));
}
function onRoomMessageReceived(payload) {
    var m = JSON.parse(payload.body); if (m.roomId !== currentRoomId) return;
    var c = conversations.get('room:' + m.roomId); if (m.roomUsers) c.members = m.roomUsers;
    receive(m, c); if (activeKey === c.key) renderHeader();
}
function connect() {
    if (stopping) return;
    connected = false; clearTimeout(reconnectTimer);
    $('connecting').classList.remove('hidden'); $('connecting').textContent = 'Connecting…';
    var client = Stomp.over(new SockJS('/ws')); stompClient = client; client.debug = null;
    client.connect({}, function() {
        if (client !== stompClient || stopping) { client.disconnect(); return; }
        connected = true; attempts = 0;
        client.subscribe('/topic/publicChatRoom', function(p) { receive(JSON.parse(p.body), conversations.get('public')); });
        client.subscribe('/user/queue/private', onPrivateMessageReceived); client.subscribe('/user/queue/room', onRoomMessageReceived);
        client.send('/app/chat.addUser', {}, '{}'); $('connecting').classList.add('hidden'); controls();
    }, function(error) {
        if (client !== stompClient || stopping) return;
        connected = false; sendGeneration++;
        if (sending) completeSend('Connection lost. Check the conversation before retrying.');
        if (currentRoomId) conversations.get('room:' + currentRoomId).joined = false;
        currentRoomId = null; roomPending = false; controls(); renderHeader();
        $('connecting').classList.remove('hidden');
        if ((error && error.command === 'ERROR') || attempts >= 5) { $('connecting').textContent = 'Connection lost. Reload or sign in again.'; return; }
        $('connecting').textContent = 'Connection lost. Reconnecting…';
        reconnectTimer = setTimeout(async function() {
            try { var response = await fetch('/', { cache: 'no-store' }); if (response.redirected && new URL(response.url).pathname === '/login') { window.location.href = '/login'; return; } } catch (ignored) {}
            connect();
        }, Math.min(1000 * Math.pow(2, attempts++), 15000));
    });
}
async function uploadImage(file) {
    if (file.size > 10 * 1024 * 1024) throw new Error('Image must be under 10MB.');
    var data = new FormData(); data.append('file', file);
    var response = await fetch('/upload', { method: 'POST', headers: { 'X-CSRF-Token': csrfToken }, body: data });
    if (response.status === 401 || response.redirected) throw new Error('Session expired. Sign in again.');
    if (!response.ok) { var detail = await response.json().catch(function() { return {}; }); throw new Error(detail.detail || detail.message || 'Upload failed. Choose a JPEG, PNG or GIF image.'); }
    var url = await response.text();
    if (!/^\/images\/[A-Za-z0-9_.-]+[.](png|jpg|jpeg|gif)$/.test(url)) throw new Error('Upload returned an invalid image. Your draft is kept.');
    return url;
}
async function sendMessage(event) {
    event.preventDefault(); if (sending) return;
    if (!ready()) { showError('Wait for chat to connect.'); return; }
    var c = active(), raw = $('message').value, content = raw.trim(), file = c.file;
    if (!content && !file) return;
    if (content.length > 2000) { showError('Messages may contain at most 2000 characters.'); return; }
    if (c.type === 'room' && !c.joined) { showError('Rejoin this room before sending.'); return; }
    if (roomPending) { showError('Wait for the room change to finish.'); return; }
    var generation = ++sendGeneration, client = stompClient;
    c.draft = raw; sending = true; controls(); showError('');
    try {
        var imageUrl = file ? await uploadImage(file) : null;
        if (generation !== sendGeneration) return;
        if (!ready() || client !== stompClient) throw new Error('Connection lost. Your draft is kept.');
        if (c.type === 'room' && (!c.joined || currentRoomId !== c.target)) throw new Error('Room membership changed. Your draft is kept.');
        pending = { key: c.key, raw: raw, content: content, file: file, imageUrl: imageUrl };
        pendingTimer = setTimeout(function() { completeSend('Message not confirmed. Check the conversation before retrying.'); }, 10000);
        stompClient.send(c.type === 'room' ? '/app/chat.sendRoomMessage' : c.type === 'direct' ? '/app/chat.sendPrivateMessage' : '/app/chat.sendMessage', {}, JSON.stringify({ content: content, recipient: c.type === 'direct' ? c.target : null, roomId: c.type === 'room' ? c.target : null, imageUrl: imageUrl }));
    } catch (error) { if (generation === sendGeneration) completeSend(error.message); }
}
function roomRequest(destination, payload) {
    if (!ready() || roomPending || sending) return;
    roomPending = true; $('roomDialogFeedback').textContent = ''; controls();
    roomTimer = setTimeout(function() {
        roomPending = false; $('roomDialogFeedback').textContent = 'Room change not confirmed. Try again.'; controls();
    }, 10000);
    try { stompClient.send(destination, {}, JSON.stringify(payload)); }
    catch (error) { clearTimeout(roomTimer); roomPending = false; showError(error.message); controls(); }
}
function createPrivateRoom() { roomRequest('/app/chat.createPrivateRoom', {}); }
function joinRoom(event) {
    if (event) event.preventDefault();
    var roomId = $('roomId').value.trim();
    if (roomId) roomRequest('/app/chat.joinRoom', { roomId: roomId });
}
function leaveRoom() { if (currentRoomId) roomRequest('/app/chat.leaveRoom', {}); }
$('messageForm').addEventListener('submit', sendMessage);
$('message').addEventListener('input', function() { active().draft = $('message').value; resize(); controls(); });
$('message').addEventListener('keydown', function(e) { if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) sendMessage(e); });
$('conversationSearch').addEventListener('input', renderList);
$('newChatButton').addEventListener('click', function() { $('newChatDialog').showModal(); $('recipient').focus(); });
$('newChatForm').addEventListener('submit', function(e) {
    e.preventDefault(); var name = $('recipient').value.trim();
    if (!/^[A-Za-z0-9_-]{1,32}$/.test(name)) return;
    var c = conversation('direct:' + name, 'direct', name, name); $('newChatDialog').close(); select(c.key);
});
$('openRoomButton').addEventListener('click', function() { $('roomDialogFeedback').textContent = ''; $('roomId').value = active().type === 'room' ? active().target : ''; $('roomDialog').showModal(); controls(); });
document.querySelectorAll('[data-close-dialog]').forEach(function(button) { button.addEventListener('click', function() { $(button.dataset.closeDialog).close(); }); });
$('createRoomButton').addEventListener('click', createPrivateRoom); $('joinRoomForm').addEventListener('submit', joinRoom); $('leaveRoomButton').addEventListener('click', leaveRoom);
$('uploadImageButton').addEventListener('click', function() { $('imageInput').click(); });
$('imageInput').addEventListener('change', function() { active().file = $('imageInput').files[0] || null; attachment(); });
$('removeImageButton').addEventListener('click', function() { active().file = null; $('imageInput').value = ''; attachment(); });
function details(open) { $('conversationDetails').hidden = !open; $('chat-container').classList.toggle('details-open', open); $('detailsButton').setAttribute('aria-expanded', String(open)); }
$('detailsButton').addEventListener('click', function() { details($('conversationDetails').hidden); });
$('closeDetailsButton').addEventListener('click', function() { details(false); });
$('backToChats').addEventListener('click', function() { details(false); $('chat-container').classList.remove('mobile-chat-open'); });
$('copyRoomButton').addEventListener('click', async function() { try { await navigator.clipboard.writeText($('inviteCode').value); toast('Room code copied'); } catch (error) { $('inviteCode').select(); toast('Select and copy the room code'); } });
['😀','😊','😂','❤️','👍','🎉','👋','🙌','✨','🔥','🤔','😎','🙏','💬','🚀'].forEach(function(emoji) {
    var button = node('button', '', emoji); button.type = 'button'; button.setAttribute('aria-label', 'Insert ' + emoji);
    button.addEventListener('click', function() { var input = $('message'); input.setRangeText(emoji, input.selectionStart, input.selectionEnd, 'end'); active().draft = input.value; input.focus(); $('emojiPicker').hidden = true; $('emojiButton').setAttribute('aria-expanded', 'false'); controls(); resize(); }); $('emojiPicker').appendChild(button);
});
$('emojiButton').addEventListener('click', function() { $('emojiPicker').hidden = !$('emojiPicker').hidden; $('emojiButton').setAttribute('aria-expanded', String(!$('emojiPicker').hidden)); });
document.addEventListener('visibilitychange', function() { if (conversationVisible(activeKey)) { active().unread = 0; renderList(); } });
document.addEventListener('keydown', function(e) { if (e.key === 'Escape') { $('emojiPicker').hidden = true; $('emojiButton').setAttribute('aria-expanded', 'false'); details(false); } });
document.addEventListener('click', function(e) { if (!e.target.closest('.emoji-container')) { $('emojiPicker').hidden = true; $('emojiButton').setAttribute('aria-expanded', 'false'); } });
$('notificationButton').addEventListener('click', async function() {
    if (!('Notification' in window)) { toast('Browser notifications unavailable'); return; }
    var permission = await Notification.requestPermission(); toast(permission === 'granted' ? 'Notifications enabled' : 'Notifications were not enabled');
});
window.addEventListener('beforeunload', function() { stopping = true; clearTimeout(reconnectTimer); clearTimeout(pendingTimer); clearTimeout(roomTimer); if (previewUrl) URL.revokeObjectURL(previewUrl); if (ready()) stompClient.disconnect(); });
avatar($('profileAvatar'), username, 'direct'); $('profileAvatar').classList.add('avatar-profile');
renderList(); renderHeader(); renderMessages(); connect();
