Pinned upstream: https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/tree/2aab9957dfd5231e5b192aecdb177f93ace4c92e
Only required API modules, their utility dependency closure and IP data are redistributed. song_url_v1.js removes the unblock import and branch; no alternate sources are supported. Remaining source files retain upstream code except the changes listed below. HTTP server, app and main entry points, unused checkToken modules and unrelated utilities are omitted.

util/request.js removes unused checkToken and proxy integrations and rejects either option. Musxi never loads main/server/app or alternate-source modules.
