(() => {
  const app = window.Telegram?.WebApp;
  const button = document.getElementById("start");
  const hangupButton = document.getElementById("hangup");\n  const answerButton = document.getElementById("answer");\n  const callButton = document.getElementById("call-sim");\n  const targetInput = document.getElementById("target");
  const status = document.getElementById("status");
  let active = false;
  let peer = null;
  let dataChannel = null;
  let roomToken = null;
  let pollTask = null;
  let audioContext = null;
  let microphone = null;
  let worklet = null;
  let sendSequence = 0n;
  let signalChain = Promise.resolve();
  let offerSent = false;
  const earlyCandidates = [];
  const pendingRemoteCandidates = [];

  if (app) { app.ready(); app.expand(); }
  else {
    status.textContent = "افتح الصفحة من Telegram Mini App.";
    button.disabled = true;
  }

  function show(message) { status.textContent = message; }

  async function request(path, options = {}) {
    const response = await fetch(path, {
      ...options,
      headers: { ...(options.headers || {}), ...(roomToken ? { Authorization: "Bearer " + roomToken } : {}) },
      cache: "no-store",
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || "تعذر الاتصال بالخادم");
    return result;
  }

  function sendSignal(type, data = {}) {
    signalChain = signalChain.then(() => request("/api/signal", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ type, data }),
    }));
    return signalChain;
  }

  async function startAudio() {
    if (audioContext) return;
    show("تم فتح قناة الصوت. اطلب إذن الميكروفون…");
    microphone = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
      video: false,
    });
    audioContext = new AudioContext();
    await audioContext.audioWorklet.addModule("/audio-worklet.js");
    worklet = new AudioWorkletNode(audioContext, "voice-pcm", {
      numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [1],
    });
    worklet.port.onmessage = (event) => {
      if (event.data?.type !== "pcm" || dataChannel?.readyState !== "open") return;
      if (dataChannel.bufferedAmount > 96 * 1024) return;
      const samples = event.data.samples;
      if (!(samples instanceof Float32Array) || samples.length !== 160) return;
      const packet = new ArrayBuffer(328);
      const view = new DataView(packet);
      view.setBigUint64(0, sendSequence++, false);
      for (let i = 0; i < 160; i++) {
        const sample = Math.max(-1, Math.min(1, samples[i]));
        view.setInt16(8 + i * 2, Math.round(sample * 32767), true);
      }
      dataChannel.send(packet);
    };
    audioContext.createMediaStreamSource(microphone).connect(worklet);
    worklet.connect(audioContext.destination);
    await audioContext.resume();
    show("الصوت جاهز. انتظر اتصال هاتف البوابة…");
  }

  function acceptPcm(packet) {
    if (!(packet instanceof ArrayBuffer) || packet.byteLength !== 328 || !worklet) return;
    const view = new DataView(packet);
    const sequenceBig = view.getBigUint64(0, false);
    if (sequenceBig > BigInt(Number.MAX_SAFE_INTEGER)) return;
    const samples = new Float32Array(160);
    for (let i = 0; i < 160; i++) samples[i] = view.getInt16(8 + i * 2, true) / 32768;
    worklet.port.postMessage({ type: "remote", sequence: Number(sequenceBig), samples }, [samples.buffer]);
  }

  async function handleSignal(message) {
    if (message.type === "answer" && message.data?.description) {
      await peer.setRemoteDescription(message.data.description);
      for (const candidate of pendingRemoteCandidates.splice(0)) await peer.addIceCandidate(candidate);
      show("تم التفاوض مع الهاتف. جارٍ فتح قناة الصوت…");
    } else if (message.type === "ice-candidate" && message.data?.candidate) {
      if (peer.remoteDescription) await peer.addIceCandidate(message.data.candidate);
      else pendingRemoteCandidates.push(message.data.candidate);
    } else if (message.type === "ringing") {
      show("هاتف البوابة يرن…");
    } else if (message.type === "accepted") {
      show("تم قبول المكالمة.");
    } else if (message.type === "hangup") {
      stop("أنهى الطرف الآخر المكالمة.");
    } else if (message.type === "error") {
      stop("تعذر إكمال المكالمة.");
    }
  }

  async function pollLoop() {
    while (active) {
      try {
        const result = await request("/api/poll");
        for (const message of result.messages || []) await handleSignal(message);
      } catch (error) {
        show("انقطع اتصال الإشارات مؤقتاً: " + error.message);
        await new Promise(resolve => setTimeout(resolve, 1200));
      }
      await new Promise(resolve => setTimeout(resolve, 250));
    }
  }

  async function start() {
    button.disabled = true;
    show("جارٍ التحقق من Telegram وإنشاء غرفة الاتصال…");
    try {
      const room = await fetch("/api/room", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ initData: app.initData }),
      }).then(async response => {
        const body = await response.json();
        if (!response.ok) throw new Error(body.error || "فشل التحقق من Telegram");
        return body;
      });
      roomToken = room.browserToken;
      const ice = await request("/api/ice");
      peer = new RTCPeerConnection({ iceServers: ice.iceServers });
      dataChannel = peer.createDataChannel("pcm", { ordered: false, maxRetransmits: 0 });
      dataChannel.binaryType = "arraybuffer";
      dataChannel.onopen = () => startAudio().catch(error => show("تعذر تشغيل الصوت: " + error.message));
      dataChannel.onmessage = event => {
        if (event.data instanceof ArrayBuffer) acceptPcm(event.data);
        else if (event.data instanceof Blob) event.data.arrayBuffer().then(acceptPcm);
      };
      dataChannel.onclose = () => show("أغلقت قناة الصوت.");
      peer.onicecandidate = event => {
        if (!event.candidate) return;
        const send = () => sendSignal("ice-candidate", { candidate: event.candidate.toJSON() });
        if (offerSent) send().catch(() => {});
        else earlyCandidates.push(event.candidate.toJSON());
      };
      peer.onconnectionstatechange = () => {
        if (peer.connectionState === "failed" || peer.connectionState === "disconnected") {
          show("تعذر إنشاء اتصال WebRTC. تحقق من TURN والشبكة.");
        }
      };
      active = true;
      pollTask = pollLoop();
      const offer = await peer.createOffer();
      await peer.setLocalDescription(offer);
      await sendSignal("offer", { description: peer.localDescription });
      offerSent = true;
      for (const candidate of earlyCandidates.splice(0)) {
        await sendSignal("ice-candidate", { candidate });
      }
      hangupButton.hidden = false;
      show("أُرسلت الدعوة إلى هاتف البوابة. الغرفة صالحة لمدة 10 دقائق.");
    } catch (error) {
      stop("تعذر بدء المكالمة: " + error.message);
      button.disabled = false;
    }
  }

  async function stop(message = "انتهت المكالمة.") {
    const wasActive = active;
    active = false;
    if (wasActive && roomToken) sendSignal("hangup").catch(() => {});
    try { dataChannel?.close(); } catch {}
    try { peer?.close(); } catch {}
    try { microphone?.getTracks().forEach(track => track.stop()); } catch {}
    try { await audioContext?.close(); } catch {}
    dataChannel = null; peer = null; microphone = null; worklet = null; audioContext = null;
    roomToken = null;
    hangupButton.hidden = true;
    show(message);
    button.disabled = false;
  }

  button.addEventListener("click", start);
  hangupButton.addEventListener("click", () => stop());
  window.addEventListener("pagehide", () => {
    if (active && roomToken) navigator.sendBeacon("/api/signal", new Blob([], { type: "application/json" }));
    active = false;
  });
})();
