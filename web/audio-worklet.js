class VoicePcmProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.inputPhase = 0;
    this.inputSum = 0;
    this.inputSamples = 0;
    this.inputFrame = new Float32Array(160);
    this.inputCount = 0;
    this.frames = new Map();
    this.expected = null;
    this.current = null;
    this.outputPosition = 0;
    this.gapSince = 0;
    this.port.onmessage = (event) => {
      const message = event.data;
      if (message?.type !== "remote" || !Number.isSafeInteger(message.sequence) ||
          !(message.samples instanceof Float32Array)) return;
      if (this.expected !== null && message.sequence < this.expected) return;
      if (!this.frames.has(message.sequence)) this.frames.set(message.sequence, message.samples);
      while (this.frames.size > 8) {
        const oldest = Math.min(...this.frames.keys());
        this.frames.delete(oldest);
        if (this.expected !== null && oldest >= this.expected) this.expected = oldest + 1;
      }
    };
  }

  process(inputs, outputs) {
    const input = inputs[0]?.[0];
    const output = outputs[0]?.[0];
    if (input) {
      for (let i = 0; i < input.length; i++) {
        // Box-filter before decimating to reduce aliasing in the 8 kHz voice band.
        this.inputPhase += 8000;
        this.inputSum += input[i];
        this.inputSamples++;
        if (this.inputPhase >= sampleRate) {
          const downsampled = this.inputSum / this.inputSamples;
          this.inputPhase -= sampleRate;
          this.inputSum = 0;
          this.inputSamples = 0;
          this.inputFrame[this.inputCount++] = downsampled;
          if (this.inputCount === 160) {
            const frame = this.inputFrame;
            this.inputFrame = new Float32Array(160);
            this.inputCount = 0;
            this.port.postMessage({ type: "pcm", samples: frame }, [frame.buffer]);
          }
        }
      }
    }

    if (output) {
      const step = 8000 / sampleRate;
      for (let i = 0; i < output.length; i++) {
        if (!this.current) this.acquireFrame();
        if (!this.current) {
          output[i] = 0;
          continue;
        }
        const index = Math.min(159, Math.floor(this.outputPosition));
        const nextIndex = Math.min(159, index + 1);
        const fraction = this.outputPosition - index;
        output[i] = this.current[index] * (1 - fraction) + this.current[nextIndex] * fraction;
        this.outputPosition += step;
        if (this.outputPosition >= 160) {
          this.current = null;
          this.outputPosition -= 160;
        }
      }
    }
    return true;
  }

  acquireFrame() {
    if (this.expected === null) {
      if (this.frames.size < 3) return;
      this.expected = Math.min(...this.frames.keys());
    }
    const frame = this.frames.get(this.expected);
    if (frame) {
      this.frames.delete(this.expected++);
      this.current = frame;
      this.gapSince = 0;
      return;
    }
    if (this.frames.size === 0) {
      this.gapSince = 0;
      return;
    }
    if (!this.gapSince) this.gapSince = currentTime || 0.000001;
    const now = currentTime || 0;
    if (now - this.gapSince >= 0.04) {
      const next = Math.min(...this.frames.keys());
      this.expected = next + 1;
      this.current = this.frames.get(next);
      this.frames.delete(next);
      this.gapSince = 0;
    }
  }
}

registerProcessor("voice-pcm", VoicePcmProcessor);
