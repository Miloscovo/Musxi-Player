// Single transport entry point. CEF Renderer router forwards to Browser via IPC.
window.musxiNative = Object.freeze({
  request(command, params = {}) {
    return new Promise((resolve, reject) => {
      let queryId;
      const timeout = setTimeout(() => {
        if (queryId !== undefined) window.cefQueryCancel(queryId);
        reject(new Error("Native request timed out"));
      }, 5000);
      const fail = (code, message) => {
        clearTimeout(timeout);
        reject(Object.assign(new Error(message), { code }));
      };
      if (!window.cefQuery) { fail(503, "Native bridge unavailable"); return; }
      queryId = window.cefQuery({
        request: JSON.stringify({ version: 1, command, params }),
        persistent: false,
        onSuccess: text => {
          clearTimeout(timeout);
          try { resolve(JSON.parse(text)); } catch (error) { reject(error); }
        },
        onFailure: fail
      });
    });
  },
  async getState() {
    const reply = await this.request("player.getState");
    if (reply.version !== 1 || typeof reply.result?.volumePercent !== "number")
      throw new Error("Invalid native state response");
    return reply.result;
  }
});
