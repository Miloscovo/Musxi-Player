"""Private JSON-lines child; credentials only return to the native DPAPI store."""
import asyncio
import base64
import json
import os
import re
from pathlib import Path
from urllib.parse import urlsplit, unquote
import sys
from datetime import datetime, timedelta, timezone

from qqmusic_api import Client, Credential
from qqmusic_api.models.login import QRLoginType, QRCodeLoginEvents
from qqmusic_api.modules.search import SearchType
from qqmusic_api.modules.song import SongFileInfo, SongFileType

class PublicError(ValueError):
    pass

class Adapter:
    def __init__(self):
        profile = (Path(os.environ['MUSXI_PROFILE_DIR']) if os.environ.get('MUSXI_PROFILE_DIR') else Path(os.environ.get('LOCALAPPDATA', str(Path.home()))) / 'MusxiPlayer') / 'qq-device.json'
        profile.parent.mkdir(parents=True, exist_ok=True)
        self.client = Client(device_path=str(profile))
        self.profile = None
        self.qr = None
        self.songs = {}
        self.lists = {}

    def session(self):
        return {'platform':'qq', 'credential':self.client.credential.model_dump() if self.profile else None, 'profile':self.profile}

    def auth(self):
        if not self.profile: raise PublicError('请先扫码登录 QQ 音乐')

    async def identify(self):
        info = await self.client.user.get_homepage(self.client.credential.encrypt_uin)
        self.profile = {'id':str(self.client.credential.musicid), 'name':info.base_info.name, 'avatar':info.base_info.avatar}

    async def membership(self):
        info = await self.client.user.get_vip_info()
        identity = info.identity
        flags = [('huge_vip', '豪华绿钻', 'huge_vip_end'), ('vip', '绿钻会员', None),
                 ('group_vip_flag', '家庭会员', 'group_vip_end'), ('cp_lover_flag', '情侣会员', 'cp_lover_end'),
                 ('child_vip', '儿童会员', None), ('exp_vip', '体验会员', None), ('ad_vip_flag', '广告会员', None),
                 ('twelve', '十二平台会员', 'twelve_end'), ('eight', '八平台会员', 'eight_end')]
        if not ({'svip', 'star', 'ystar'} & info.model_fields_set or
                {field for field, _, _ in flags} & identity.model_fields_set):
            raise PublicError('QQ 会员状态暂不可用')
        name, end = '非会员', None
        if info.svip > 0:
            name = '超级会员'
        elif info.star > 0 or info.ystar > 0:
            name, end = '星级会员', info.star_end or info.ystar_end
        else:
            for field, label, date_field in flags:
                if getattr(identity, field) > 0:
                    name, end = label, getattr(identity, date_field) if date_field else None
                    break
        if name == '非会员' and not {'vip', 'huge_vip'} <= identity.model_fields_set:
            raise PublicError('QQ 会员状态暂不可用')
        result = {'name':name}
        if name != '非会员':
            end = end or info.userinfo.expire
            if end:
                try:
                    raw = str(end).strip()
                    if raw.isdigit() and len(raw) in (10, 13):
                        result['expiresAt'] = int(raw) * (1000 if len(raw) == 10 else 1)
                    else:
                        date = datetime.strptime(raw, '%Y%m%d') if raw.isdigit() and len(raw) == 8 else datetime.fromisoformat(raw)
                        result['expiresAt'] = int(date.replace(tzinfo=date.tzinfo or timezone(timedelta(hours=8))).timestamp()*1000)
                except (ValueError, OverflowError):
                    pass
        if 'expiresAt' in result and not 0 < result['expiresAt'] <= 253402300799999:
            del result['expiresAt']
        return result

    def song(self, song):
        row = {'id':'qq:'+song.mid, 'serviceId':song.mid, 'platform':'qq', 'mediaMid':song.file.media_mid,
               'name':song.name, 'artist':'、'.join(s.name for s in song.singer), 'album':song.album.name,
               'cover':song.album.cover_url(300), 'duration':song.interval*1000, 'count':0, 'editable':False}
        self.songs[row['id']] = song
        return row

    def playlist(self, p, editable):
        return {'id':'qq:'+str(p.id), 'serviceId':str(p.id), 'platform':'qq', 'name':p.title, 'artist':'',
                'cover':p.picurl, 'duration':0, 'count':p.songnum, 'editable':editable, 'favorite':p.dirid==201}

    async def sync(self):
        self.auth()
        created = await self.client.user.get_created_songlist(self.client.credential.musicid)
        if not created.finished: raise PublicError('QQ 自建歌单尚未完整返回，请稍后重试')
        pairs = [(p, True) for p in created.playlists if not p.invalid]
        for page in range(1, 101):
            fav = await self.client.user.get_fav_songlist(self.client.credential.encrypt_uin, page=page, num=100)
            if fav.hide: raise PublicError('QQ 收藏歌单被隐藏，请在官方客户端检查隐私设置')
            pairs.extend((p, False) for p in fav.playlists)
            if not fav.hasmore: break
        else: raise PublicError('QQ 歌单超过当前上限')
        self.lists = {self.playlist(p, editable)['id']:(p, editable) for p, editable in pairs}
        return [self.playlist(p, editable) for p, editable in self.lists.values()]

    async def tracks(self, id):
        self.auth()
        if id not in self.lists: raise PublicError('请先同步 QQ 歌单')
        p, editable = self.lists[id]
        rows = []
        for page in range(1, 101):
            result = await self.client.songlist.get_detail(p.id, dirid=p.dirid, num=100, page=page)
            rows.extend(self.song(s) for s in result.songs)
            if not result.hasmore: break
        else: raise PublicError('QQ 歌单歌曲超过当前上限')
        return {'playlist':self.playlist(p, editable), 'tracks':rows}

    async def detail(self, id):
        if id not in self.songs:
            result = await self.client.song.get_detail(id.removeprefix('qq:'))
            self.song(result.track)
        return self.songs[id]

    async def audio(self, song, quality):
        filetype = {'128':SongFileType.MP3_128, '320':SongFileType.MP3_320, 'flac':SongFileType.FLAC}.get(quality)
        if filetype is None: raise PublicError('无效音质')
        result = await self.client.song.get_song_urls([SongFileInfo(mid=song.mid, media_mid=song.file.media_mid, song_type=song.type)], file_type=filetype)
        entry = result.data[0] if result.data else None
        if not entry or entry.result != 0 or not entry.purl: raise PublicError('此账号暂无该音质播放权限')
        expected = filetype.s + (song.file.media_mid or song.mid + song.mid) + filetype.e
        if entry.filename != expected or Path(unquote(urlsplit(entry.purl).path)).name != expected or entry.ekey:
            raise PublicError('所选音质未返回可验证的原始文件，已保留当前播放')
        dispatch = await self.client.song.get_cdn_dispatch()
        if not dispatch.sip: raise PublicError('QQ 音频服务器地址不可用')
        return {'url':dispatch.sip[0]+entry.purl, 'track':self.song(song), 'quality':quality}

    async def liked(self, song):
        for page in range(1,101):
            result = await self.client.user.get_fav_song(self.client.credential.encrypt_uin, page=page, num=100)
            if any(row.mid==song.mid for row in result.songs): return True
            if not result.hasmore: return False
        raise PublicError('QQ 收藏记录超过当前上限')

    async def run(self, r):
        op = r['op']
        if op=='lyrics':
            if not re.fullmatch(r'qq:[A-Za-z0-9]+', r.get('id', '')):
                raise PublicError('歌曲标识无效')
            saved = r.get('session') or {}
            self.client.credential = Credential.model_validate(saved['credential']) if saved.get('credential') else Credential()
            # 0.8.1 decrypts the response model; qrc=False requests line-level LRC.
            result = await self.client.lyric.get_lyric(r['id'][3:], qrc=False, trans=True, roma=False)
            text = result.lyric
            if not isinstance(text, str) or len(text.encode('utf-8'))>1024*1024 or '\0' in text:
                raise PublicError('歌词内容无效')
            if not text.strip(): return {'outcome':'none'}
            # The SDK suppresses decrypt errors; never display/cache leftover ciphertext.
            if re.fullmatch(r'[0-9a-fA-F]+', text.strip()): raise PublicError('歌词解码失败')
            found = {'outcome':'found', 'format':'lrc', 'text':text, 'translationState':'unknown'}
            trans = getattr(result, 'trans', None)
            if trans is None or (isinstance(trans, str) and not trans.strip()): found['translationState'] = 'none'
            # A damaged translation keeps the original usable and stays unknown for a later retry.
            elif isinstance(trans, str) and len(trans.encode('utf-8'))<=1024*1024 and '\0' not in trans and \
                    not re.fullmatch(r'[0-9a-fA-F]+', trans.strip()):
                found['translationState'] = 'available'; found['translation'] = trans
            return found
        if op=='init':
            saved = r.get('session') or {}
            if saved.get('credential'):
                self.client.credential = Credential.model_validate(saved['credential'])
                if not await self.client.login.check_expired(): await self.identify()
            return {'connected':bool(self.profile), 'profile':self.profile, 'session':self.session()}
        if op=='qr':
            self.qr = await self.client.login.get_qrcode(QRLoginType.QQ)
            return {'image':'data:image/png;base64,'+base64.b64encode(self.qr.data).decode(), 'status':'waiting'}
        if op=='poll':
            if not self.qr: raise PublicError('请重新生成 QQ 二维码')
            result = await self.client.login.check_qrcode(self.qr)
            if result.event==QRCodeLoginEvents.DONE:
                self.client.credential = result.credential
                await self.identify()
                self.qr = None
                return {'status':'connected', 'profile':self.profile, 'session':self.session()}
            return {'status':'expired' if result.event in (QRCodeLoginEvents.TIMEOUT, QRCodeLoginEvents.REFUSE) else 'confirm' if result.event==QRCodeLoginEvents.CONF else 'waiting'}
        if op=='logout':
            self.profile=None;self.qr=None;self.lists={};self.client.credential=Credential()
            return {'connected':False}
        if op=='search':
            result = await self.client.search.search_by_type(r['keywords'], SearchType.SONG, num=30, page=r['page'], highlight=False)
            return {'tracks':[self.song(s) for s in result.song], 'total':result.total_num, 'hasMore':bool(result.nextpage)}
        self.auth()
        if op=='sync': return {'playlists':await self.sync(), 'session':self.session()}
        if op=='tracks': return await self.tracks(r['id'])
        if op=='avatar': return {'url':self.profile.get('avatar','')}
        if op=='membership': return await self.membership()
        song = await self.detail(r['id'])
        if op=='audio': return await self.audio(song, r.get('quality','128'))
        if op=='qualities':
            async def probe(quality, name):
                try:
                    await self.audio(song, quality)
                    return {'id':quality,'name':name}
                except Exception: return None
            options = await asyncio.gather(*(probe(quality, name) for quality, name in [
                ('128','标准 · 128 kbps'),('320','高品质 · 320 kbps'),('flac','无损 · FLAC')]))
            return {'id':r['id'],'options':[option for option in options if option]}
        if op=='song_menu':
            lists=await self.sync()
            return {'id':r['id'], 'liked':await self.liked(song), 'canFavorite':True, 'playlists':[p for p in lists if p['editable']]}
        if op=='song_update':
            await self.sync()
            if r['kind']=='favorite':
                action = self.client.songlist.like_song if r['enabled'] else self.client.songlist.unlike_song
                if not await action([(song.id,song.type)]): raise PublicError('QQ 未确认收藏操作')
                if await self.liked(song)!=r['enabled']: raise PublicError('操作已提交，暂未确认收藏结果')
                target=next((id for id,(p,_) in self.lists.items() if p.dirid==201),None)
            else:
                target=r['playlistId']
                if target not in self.lists or not self.lists[target][1]: raise PublicError('目标歌单不可编辑')
                p,_=self.lists[target]
                if not await self.client.songlist.add_songs(p.dirid,[(song.id,song.type)],tid=p.id): raise PublicError('QQ 未确认添加操作')
            if not target: raise PublicError('未找到 QQ 我喜欢歌单，请重新同步')
            result=await self.tracks(target)
            if r['kind']=='add' and not any(s['id']==r['id'] for s in result['tracks']): raise PublicError('操作已提交，暂未确认添加结果')
            return {**result,'playlists':await self.sync(),'message':'歌单操作已完成'}
        raise PublicError('不支持的 QQ 操作')

async def main():
    adapter=Adapter()
    while True:
        line=await asyncio.to_thread(sys.stdin.readline)
        if not line: break
        try:
            if len(line)>16*1024*1024: raise PublicError('请求过大')
            result=await asyncio.wait_for(adapter.run(json.loads(line)),timeout=80)
            reply={'ok':True,'data':result}
        except PublicError as error:
            # Only our own validation errors are public. Pydantic errors may include credentials.
            reply={'ok':False,'error':str(error)}
        except Exception:
            reply={'ok':False,'error':'QQ 接口请求失败，请检查网络或在官方客户端确认账号状态'}
        sys.stdout.write(json.dumps(reply,ensure_ascii=False)+'\n');sys.stdout.flush()
    await adapter.client.close()

if __name__=='__main__': asyncio.run(main())
