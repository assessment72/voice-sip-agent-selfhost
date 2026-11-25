(() => {
  const telegram = window.Telegram?.WebApp;
  const startButton = document.getElementById("start");
  const hangupButton = document.getElementById("hangup");
  const answerButton = document.getElementById("answer");
  const callButton = document.getElementById("call-sim");
  const targetInput = document.getElementById("target");
  const status = document.getElementById("status");
  let active = false, peer = null, dataChannel = null, roomToken = null;
  let audioContext = null, microphone = null, worklet = null;
  let sendSequence = 0n, signalChain = Promise.resolve(), offerSent = false;
  const earlyCandidates = [], pendingRemoteCandidates = [];

  if (telegram) { telegram.ready(); telegram.expand(); }
  else { status.textContent = "افتح الصفحة من Telegram Mini App."; startButton.disabled = true; }

  const show = message => { status.textContent = message; };
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
    signalChain = signalChain.catch(() => {}).then(() => request("/api/signal", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ type, data }),
    }));
    return signalChain;
  }

  async function startAudio() {
    if (worklet) return;
    if (!microphone) throw new Error("لم يتم منح إذن الميكروفون");
    if (!audioContext) audioContext = new AudioContext();
    await audioContext.audioWorklet.addModule("/audio-worklet.js");
    worklet = new AudioWorkletNode(audioContext, "voice-pcm", {
      numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [1],
    });
    worklet.port.onmessage = event => {
      const samples = event.data?.samples;
      if (event.data?.type !== "pcm" || dataChannel?.readyState !== "open" ||
          !(samples instanceof Float32Array) || samples.length !== 160 || dataChannel.bufferedAmount > 96 * 1024) return;
      const packet = new ArrayBuffer(328), view = new DataView(packet);
      view.setBigUint64(0, sendSequence++, false);
      for (let i = 0; i < 160; i++) {
        view.setInt16(8 + i * 2, Math.round(Math.max(-1, Math.min(1, samples[i])) * 32767), true);
      }
      dataChannel.send(packet);
    };
    audioContext.createMediaStreamSource(microphone).connect(worklet);
    worklet.connect(audioContext.destination);
    await audioContext.resume();
    callButton.disabled = false;
    show("الصوت جاهز. انتظر اتصال هاتف البوابة…");
  }

  function acceptPcm(packet) {
    if (!(packet instanceof ArrayBuffer) || packet.byteLength !== 328 || !worklet) return;
    const view = new DataView(packet), seq = view.getBigUint64(0, false);
    if (seq > BigInt(Number.MAX_SAFE_INTEGER)) return;
    const samples = new Float32Array(160);
    for (let i = 0; i < 160; i++) samples[i] = view.getInt16(8 + i * 2, true) / 32768;
    worklet.port.postMessage({ type: "remote", sequence: Number(seq), samples }, [samples.buffer]);
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
      if (message.data?.target) {
        answerButton.hidden = false;
        show("مكالمة واردة من شريحة الهاتف. اضغط قبول للرد.");
      } else {
        show("جارٍ رنين الهاتف المطلوب عبر شريحة SIM…");
      }
    } else if (message.type === "accepted") {
      answerButton.hidden = true;
      show("تم قبول المكالمة.");
    } else if (message.type === "hangup") {
      await stop("أنهى الطرف الآخر المكالمة.");
    } else if (message.type === "error") {
      await stop("تعذر إكمال المكالمة.");
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
      await new Promise(resolve => setTimeout(resolve, peer?.connectionState === "connected" ? 1000 : 250));
    }
  }

  async function start() {
    startButton.disabled = true;
    active = true;
    show("اطلب إذن الميكروفون لبدء المكالمة…");
    try {
      // Create/resume AudioContext and request microphone directly from the user gesture.
      audioContext = new AudioContext();
      const audioResume = audioContext.resume();
      microphone = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true }, video: false,
      });
      await audioResume;
      show("جارٍ التحقق من Telegram وإنشاء غرفة الاتصال…");
      const response = await fetch("/api/room", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ initData: telegram.initData }),
      });
      const room = await response.json();
      if (!response.ok) throw new Error(room.error || "فشل التحقق من Telegram");
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
      peer.onicecandidate = event => {
        if (!event.candidate) return;
        const candidate = event.candidate.toJSON();
        if (offerSent) sendSignal("ice-candidate", { candidate }).catch(() => {});
        else earlyCandidates.push(candidate);
      };
      peer.onconnectionstatechange = () => {
        if (peer.connectionState === "failed") show("فشل WebRTC. تحقق من TURN والشبكة.");
      };
      pollLoop();
      await peer.setLocalDescription(await peer.createOffer());
      await sendSignal("offer", { description: peer.localDescription });
      offerSent = true;
      for (const candidate of earlyCandidates.splice(0)) await sendSignal("ice-candidate", { candidate });
      hangupButton.hidden = false;
      callButton.disabled = true;
      show("أُرسلت الدعوة إلى هاتف البوابة. انتظر فتح قناة الصوت.");
    } catch (error) {
      await stop("تعذر بدء المكالمة: " + error.message);
      startButton.disabled = false;
    }
  }

  async function stop(message = "انتهت المكالمة.") {
    const wasActive = active;
    active = false;
    if (wasActive && roomToken) { try { await sendSignal("hangup"); } catch {} }
    try { dataChannel?.close(); } catch {}
    try { peer?.close(); } catch {}
    try { microphone?.getTracks().forEach(track => track.stop()); } catch {}
    try { await audioContext?.close(); } catch {}
    dataChannel = null; peer = null; microphone = null; worklet = null; audioContext = null;
    roomToken = null; hangupButton.hidden = true; answerButton.hidden = true; callButton.disabled = true;
    show(message); startButton.disabled = false;
  }

  startButton.addEventListener("click", start);
  hangupButton.addEventListener("click", () => stop());
  answerButton.addEventListener("click", async () => {
    answerButton.disabled = true;
    try { await sendSignal("accepted"); answerButton.hidden = true; show("تم قبول المكالمة."); }
    catch (error) { show("تعذر قبول المكالمة: " + error.message); answerButton.disabled = false; }
  });
  callButton.addEventListener("click", async () => {
    const target = targetInput.value.trim();
    if (!/^\+?[0-9*#]{3,32}$/.test(target)) { show("أدخل رقم هاتف صحيحاً."); return; }
    callButton.disabled = true;
    try { await sendSignal("dial", { target }); show("جارٍ طلب الاتصال عبر شريحة الهاتف…"); }
    catch (error) { show("تعذر إرسال طلب الاتصال: " + error.message); callButton.disabled = false; }
  });
  window.addEventListener("pagehide", () => { active = false; });
})();
