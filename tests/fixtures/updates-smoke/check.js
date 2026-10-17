// Runs only via the explicit native smoke flag. No product UI or real network.
const request = (command, params = {}, persistent = false) => new Promise((resolve, reject) => {
  window.cefQuery({ request: JSON.stringify({version: 1, command, params}), persistent,
    onSuccess: text => resolve(JSON.parse(text).result),
    onFailure: (code, message) => reject({code, message}) });
});
const require = (condition, message) => { if (!condition) throw Error(message); };
const rejected = async (command, params, code, persistent = false) => {
  try { await request(command, params, persistent); } catch (error) {
    require(error.code === code, `${command}: expected ${code}, got ${JSON.stringify(error)}`); return;
  }
  throw Error(`${command}: unexpectedly accepted`);
};
const wait = async probe => {
  const until = Date.now() + 7000;
  while (Date.now() < until) { if (await probe()) return; await new Promise(r => setTimeout(r, 50)); }
  throw Error('Update fixture timed out');
};
(async () => {
  if (new URLSearchParams(location.search).has('desktopLyrics')) {
    for (const command of ['updates.getState', 'updates.check', 'updates.setPreferences', 'updates.ignoreRelease', 'updates.fixtureDiagnostics', 'updates.openDownload', 'updates.openReleaseNotes', 'updates.startDownload', 'updates.cancelDownload', 'updates.install']) {
      await rejected(command, {}, 403);
    }
    await request('test.desktopReport', {updatesDenied: true});
    return;
  }
  const state = await request('updates.getState');
  if (state.current.commit === 'update-fixture') {
    require(state.automaticCheckEnabled === true, 'Fixture release override not active');
    await rejected('updates.fixtureDiagnostics', {}, 400, true);
    await rejected('updates.ignoreRelease', {releaseId: '19'}, 409);
    await request('updates.check');
    await wait(async () => (await request('updates.getState')).checkStatus !== 'checking');
    const available = await request('updates.getState');
    require(available.result === 'update_available' && available.latest.releaseId === '20', 'Fixture selection failed');
    const ignored = await request('updates.ignoreRelease', {releaseId: '20'});
    require(ignored.preferences.ignoredReleaseId === '20', 'Fixture ignore failed');
    await request('updates.setPreferences', {autoCheck: false});
    await rejected('updates.openDownload', {releaseId: '19', assetId: '120'}, 409);
    await request('updates.openDownload', {releaseId: '20', assetId: '120'});
    await request('updates.openReleaseNotes', {releaseId: '20'});
    const diagnostics = await request('updates.fixtureDiagnostics');
    require(diagnostics.fixtureMode === true && diagnostics.fetchCount === 1 && diagnostics.openedUrls.length === 2,
      'Fixture did not record browser actions');
    require(diagnostics.openedUrls[0] === 'https://github.com/Miloscovo/Musxi-Player/releases/download/v0.5.0/MusxiPlayer-Setup-0.5.0.exe',
      'Fixture recorded wrong download URL');
    await request('desktopLyrics.setVisible', {visible: true});
    await wait(async () => (await request('test.desktop')).page?.updatesDenied === true);
    await request('desktopLyrics.setVisible', {visible: false});
    if (available.capabilities?.installerDownload) {
      const ids = {releaseId: '20', assetId: '120'};
      require(available.latest.inAppDownloadAvailable === true, 'Download capability lost manifest');
      await rejected('updates.startDownload', {url: 'https://example.invalid'}, 400);
      await rejected('updates.install', {...ids, path: 'cmd.exe'}, 400);
      await rejected('updates.startDownload', ids, 400, true);
      await rejected('updates.startDownload', {releaseId: '19', assetId: '120'}, 409);
      await rejected('updates.install', ids, 409);
      await request('updates.startDownload', ids);
      await wait(async () => (await request('updates.getState')).download.status === 'downloading');
      await request('updates.cancelDownload');
      await wait(async () => (await request('updates.getState')).download.status === 'cancelled');
      await request('updates.startDownload', ids);
      await wait(async () => (await request('updates.getState')).download.canInstall === true);
      const ready = await request('updates.getState');
      require(ready.download.receivedBytes === 3 && ready.download.status === 'ready', 'Fixture bytes not verified');
      const installing = await request('updates.install', ids);
      require(installing.download.status === 'installing', 'Install state not returned');
      require((await request('updates.fixtureDiagnostics')).installedReleases.join(',') === '20', 'Fixture did not record install');
    }
    await request('test.complete');
    return;
  }
  require(state.automaticCheckEnabled === false, 'Smoke mode enabled automatic network');
  require(state.source === 'Miloscovo/Musxi-Player', 'Wrong update source');
  require(state.preferences.ignoredReleaseId === '', 'Ignore preference not advertised');
  await rejected('updates.ignoreRelease', {releaseId: ''}, 500);
  await rejected('updates.ignoreRelease', {releaseId: '1'}, 409);
  await rejected('updates.ignoreRelease', {releaseId: 1}, 400);
  await rejected('updates.fixtureDiagnostics', {}, 404);
  await rejected('updates.getState', {}, 400, true);
  await rejected('updates.check', {url: 'https://example.invalid'}, 400);
  await rejected('updates.openDownload', {releaseId: '1', assetId: '2', url: 'file:///cmd.exe'}, 400);
  await rejected('updates.openDownload', {releaseId: '1', assetId: '2'}, 409);
  await rejected('updates.setPreferences', {autoCheck: false}, 500);
  await request('updates.check');
  await wait(async () => (await request('updates.getState')).checkStatus !== 'checking');
  const failed = await request('updates.getState');
  require(failed.checkStatus === 'failed' && failed.reason === 'network_disabled', 'Smoke performed real update I/O');
  await request('desktopLyrics.setVisible', {visible: true});
  await wait(async () => (await request('test.desktop')).page?.updatesDenied === true);
  await request('desktopLyrics.setVisible', {visible: false});
  // Existing test.complete returns a bare {} before closing the application.
  await request('test.complete');
})().catch(error => {
  console.error('CEF smoke failed: update bridge ' + (error.stack || JSON.stringify(error)));
  request('window.close').catch(() => {});
});
