const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/main.js'), 'utf8');
function app() {
    const elements = new Map();
    const makeElement = () => ({ textContent: '', content: 'csrf-token', value: '', files: [], hidden: false,
        disabled: false, children: [], style: {}, classList: { add() {}, remove() {}, toggle() {} }, setAttribute() {}, removeAttribute() {}, close() {},
        replaceChildren() { this.children = []; }, appendChild(child) { this.children.push(child); },
        addEventListener() {}, scrollTo() {}, click() {} });
    const query = selector => { if (!elements.has(selector)) elements.set(selector, makeElement()); return elements.get(selector); };
    query('#username').textContent = 'alice';
    const sent = [], subscribed = [];
    const client = { connected: true, connect(headers, success) { success(); },
        subscribe(destination) { subscribed.push(destination); return { id: 'subscription' }; },
        send(destination, headers, body) { sent.push({ destination, payload: JSON.parse(body) }); }, disconnect() {} };
    const context = { document: { hidden: false, querySelector: query, querySelectorAll() { return []; }, addEventListener() {}, createElement: makeElement },
        navigator: {}, window: { addEventListener() {}, location: {} }, Stomp: { over() { return client; } }, SockJS: function() {},
        Date, URL, console, setTimeout() {}, clearTimeout() {}, FormData: class { append() {} },
        fetch: async () => ({ ok: false, status: 400, json: async () => ({ message: 'Invalid image' }) }) };
    vm.createContext(context); vm.runInContext(source, context);
    return { context, query, sent, subscribed };
}
test('room acknowledgment controls leave button and leaving needs no stale subscription', () => {
    const { context, query, subscribed, sent } = app();
    context.onPrivateMessageReceived({ body: JSON.stringify({ type: 'ROOM_JOINED', roomId: 'room-1', roomUsers: ['alice'] }) });
    assert.equal(context.currentRoomId, 'room-1');
    assert.equal(query('#leaveRoomButton').hidden, false);
    context.leaveRoom();
    assert.equal(sent.at(-1).destination, '/app/chat.leaveRoom');
    context.onPrivateMessageReceived({ body: JSON.stringify({ type: 'ROOM_LEFT' }) });
    assert.equal(context.currentRoomId, null);
    assert.equal(query('#leaveRoomButton').hidden, true);
    assert.deepEqual(subscribed, ['/topic/publicChatRoom', '/user/queue/private', '/user/queue/room']);
});

test('private messages stay separate and increment unread counts', () => {
    const {context,query}=app();
    context.onPrivateMessageReceived({body:JSON.stringify({type:'PRIVATE',sender:'bob',recipient:'alice',content:'hello'})});
    assert.equal(context.conversations.get('direct:bob').unread,1);
    assert.equal(query('#messageArea').children.length,0);
    context.select('direct:bob');
    assert.equal(context.active().unread,0);
    assert.equal(context.active().messages[0].content,'hello');
    context.select('public');
    assert.equal(query('#messageArea').children.length,0);
});
test('server rejection retains private draft and does not spoof sender', async () => {
    const {context,query,sent}=app();
    context.conversation('direct:bob','direct','bob','bob'); context.select('direct:bob');
    query('#message').value='keep this';
    await context.sendMessage({preventDefault(){}});
    assert.equal(sent.at(-1).destination,'/app/chat.sendPrivateMessage');
    assert.equal(sent.at(-1).payload.sender,undefined);
    assert.equal(query('#message').value,'keep this');
    context.onPrivateMessageReceived({body:JSON.stringify({type:'ERROR',content:'Recipient offline'})});
    assert.equal(query('#message').value,'keep this');
    assert.equal(context.sending,false);
});
test('server echo clears confirmed draft only', async () => {
    const {context,query}=app();
    query('#message').value='hello';
    await context.sendMessage({preventDefault(){}});
    context.receive({sender:'alice',type:'CHAT',content:'hello'},context.active());
    assert.equal(query('#message').value,'');
    assert.equal(context.sending,false);
});

test('upload failure retains attachment and draft',async()=>{
 const {context,query}=app();
 context.active().file={name:'bad.png',size:10};query('#message').value='keep image';
 await context.sendMessage({preventDefault(){}});
 assert.equal(query('#message').value,'keep image');
 assert.equal(context.active().file.name,'bad.png');
 assert.equal(query('#feedback').textContent,'Invalid image');
 assert.equal(context.sending,false);
});

test('room changes are blocked during an upload',async()=>{
 const {context,sent}=app(); context.sending=true;
 const before=sent.length;context.createPrivateRoom();context.joinRoom();
 assert.equal(sent.length,before);assert.equal(context.roomPending,false);
});
test('mobile sidebar receives unread even for selected conversation',()=>{
 const {context}=app();
 context.window.matchMedia=()=>({matches:true});
 context.$('chat-container').classList.contains=()=>false;
 context.receive({type:'CHAT',sender:'bob',content:'hidden chat'},context.active());
 assert.equal(context.active().unread,1);
});
test('room operation prevents sending before membership acknowledgment',async()=>{
 const {context,query,sent}=app(); context.roomPending=true;query('#message').value='draft';
 const before=sent.length;await context.sendMessage({preventDefault(){}});
 assert.equal(sent.length,before);assert.equal(query('#message').value,'draft');
});
test('an upload cannot send through a replacement connection',async()=>{
 const {context,query,sent}=app();let finish;
 context.uploadImage=()=>new Promise(resolve=>finish=resolve);
 context.active().file={name:'image.png'};query('#message').value='draft';
 const attempt=context.sendMessage({preventDefault(){}});
 context.sendGeneration++;context.connected=false;context.completeSend('Disconnected');
 finish('/images/test.png');await attempt;
 assert.equal(sent.length,1);assert.equal(query('#message').value,'draft');assert.equal(context.pending,null);
});
