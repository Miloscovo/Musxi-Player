// Musxi: removed alternate-source unblock integration. Official account-authorized URLs only.
// 歌曲链接 - v1
// 此版本不再采用 br 作为音质区分的标准
// 而是采用 standard, exhigh, lossless, hires, jyeffect(高清臻音), vivid(臻音全景声), jymaster(超清母带), sky(沉浸环绕声) 进行音质判断
// 当unblock为true时, 会尝试使用unblockmusic-utils进行解锁, 同时音质设置不会生效, 但仍然为必须传入参数
// 当level为sky时, 可通过 immerseType 选择沉浸声类型, 支持 c512(新版c51类型)、ste2(新版环绕立体声类型)、aac2(新版aac类型)、c51(c51类型)、ste(环绕立体声类型)、aac(aac类型), 默认为 c51

const logger = require('../util/logger.js')
const createOption = require('../util/option.js')
const { cookieToJson } = require('../util/index.js')
module.exports = async (query, request) => {
  const data = {
    ids: '[' + query.id + ']',
    level: query.level,
    encodeType: 'flac',
  }
  const options = createOption(query, 'xeapi')
  if (data.level == 'sky') {
    data.immerseType = query.immerseType || 'c51'
  }
  if (data.level == 'vivid') {
    data.encodeType = 'mp3'
    const cookie = options.cookie
    options.cookie = {
      ...(typeof cookie === 'string' ? cookieToJson(cookie) : cookie),
      os: 'android',
      appver: '9.5.61',
    }
  }
  return request(`/api/song/enhance/player/url/v1`, data, options)
}
