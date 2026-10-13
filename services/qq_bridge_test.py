import asyncio
import unittest
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
from types import SimpleNamespace as S
from unittest.mock import AsyncMock
from qq_bridge import Adapter, Credential, QRCodeLoginEvents, PublicError
from qqmusic_api.core.response import snapshot_payload
from qqmusic_api.models.user import UserVipInfoResponse
from niquests.cookies import RequestsCookieJar

class CookieSnapshotTests(unittest.TestCase):
    def test_qq_confirmation_empty_cookie_keeps_login_response_readable(self):
        jar=RequestsCookieJar()
        jar.set('normal','synthetic',domain='example.test',path='/')
        jar.set('cleared','',domain='example.test',path='/')
        response=S(status_code=200,url='https://example.test/',headers={},cookies=jar,content=b'login-state',text='login-state')
        payload=snapshot_payload(response)
        self.assertEqual(payload.cookies,{'normal':'synthetic','cleared':''})
        self.assertIs(response.cookies,jar)
        self.assertEqual(payload.text,'login-state')

class QQTests(unittest.IsolatedAsyncioTestCase):
    async def test_lyrics_without_login_or_song_detail(self):
        self.a.client.lyric=S(get_lyric=AsyncMock(return_value=S(lyric='[00:01.00]Original')))
        request={'op':'lyrics','id':'qq:abc123'}
        self.assertEqual(await self.a.run(request),{'outcome':'found','format':'lrc','text':'[00:01.00]Original','translationState':'none'})
        self.a.client.lyric.get_lyric.assert_awaited_once_with('abc123',qrc=False,trans=True,roma=False)
        self.a.client.lyric.get_lyric.return_value=S(lyric='[00:01.00]Original',trans='[00:01.00]原文')
        self.assertEqual(await self.a.run(request),{'outcome':'found','format':'lrc','text':'[00:01.00]Original',
            'translationState':'available','translation':'[00:01.00]原文'})
        # Undecrypted or damaged translations never break the original.
        for trans in ['abc123ff','x\0y','x'*(1024*1024+1),b'bytes']:
            self.a.client.lyric.get_lyric.return_value=S(lyric='[00:01.00]Original',trans=trans)
            result=await self.a.run(request)
            self.assertEqual((result['text'],result['translationState']),('[00:01.00]Original','unknown'))
            self.assertNotIn('translation',result)
        self.a.client.lyric.get_lyric.return_value=S(lyric='[00:01.00]Original',trans='  ')
        self.assertEqual((await self.a.run(request))['translationState'],'none')
        self.a.client.lyric.get_lyric.return_value=S(lyric='')
        self.assertEqual(await self.a.run(request),{'outcome':'none'})
        for text in ['abc123ff', 'x\0y', 'x'*(1024*1024+1)]:
            self.a.client.lyric.get_lyric.return_value=S(lyric=text)
            with self.assertRaises(PublicError): await self.a.run(request)
        self.a.client.lyric.get_lyric.side_effect=RuntimeError('network')
        with self.assertRaises(RuntimeError): await self.a.run(request)

    async def asyncSetUp(self):
        self.a=Adapter()
        await self.a.client.close()
        self.a.client=S(credential=Credential(), login=S(), user=S(), song=S())

    async def test_qr_states_and_credential_assignment(self):
        self.a.qr=S(data=b'png')
        self.a.client.login.check_qrcode=AsyncMock(return_value=S(event=QRCodeLoginEvents.CONF))
        self.assertEqual((await self.a.run({'op':'poll'}))['status'],'confirm')
        credential=Credential(musicid=123,encrypt_uin='account')
        self.a.client.login.check_qrcode=AsyncMock(return_value=S(event=QRCodeLoginEvents.DONE,credential=credential))
        self.a.client.user.get_homepage=AsyncMock(return_value=S(base_info=S(name='User',avatar='https://qpic.cn/avatar.jpg')))
        result=await self.a.run({'op':'poll'})
        self.assertEqual(result['status'],'connected')
        self.assertIs(self.a.client.credential,credential)
        self.assertIsNone(self.a.qr)
        self.assertEqual((await self.a.run({'op':'avatar'}))['url'],'https://qpic.cn/avatar.jpg')
        await self.a.run({'op':'logout'})
        self.assertIsNone(self.a.profile)

    async def test_no_auth_or_missing_entitlement_never_produces_audio(self):
        with self.assertRaises(PublicError): await self.a.run({'op':'tracks','id':'qq:1'})
        self.a.client.song.get_song_urls=AsyncMock(return_value=S(data=[S(result=1,purl='')]))
        with self.assertRaises(PublicError): await self.a.audio(S(mid='song',file=S(media_mid='media'),type=0),'128')

    async def test_membership_requires_login_and_distinguishes_missing_fields(self):
        with self.assertRaises(PublicError): await self.a.run({'op':'membership'})
        self.a.profile={'id':'synthetic'}
        self.a.client.user.get_vip_info=AsyncMock(return_value=UserVipInfoResponse.model_validate({}))
        with self.assertRaises(PublicError): await self.a.run({'op':'membership'})
        self.a.client.user.get_vip_info=AsyncMock(return_value=UserVipInfoResponse.model_validate({'svip':0}))
        with self.assertRaises(PublicError): await self.a.run({'op':'membership'})
        self.a.client.user.get_vip_info=AsyncMock(return_value=UserVipInfoResponse.model_validate({'identity':{'vip':0,'HugeVip':0},'svip':0}))
        self.assertEqual(await self.a.run({'op':'membership'}),{'name':'非会员'})

    async def test_membership_names_and_expiry_units(self):
        self.a.profile={'id':'synthetic'}
        for payload, expected in [
            ({'svip':1,'userinfo':{'expire':1800000000}}, {'name':'超级会员','expiresAt':1800000000000}),
            ({'identity':{'HugeVip':1,'HugeVipEnd':'2028-01-01'}}, {'name':'豪华绿钻','expiresAt':1830268800000}),
            ({'identity':{'vip':1},'userinfo':{'expire':1800000000000}}, {'name':'绿钻会员','expiresAt':1800000000000}),
            ({'identity':{'HugeVip':1,'HugeVipEnd':'20280101'}}, {'name':'豪华绿钻','expiresAt':1830268800000}),
            ({'identity':{'HugeVip':1,'HugeVipEnd':'invalid'}}, {'name':'豪华绿钻'}),
        ]:
            with self.subTest(payload=payload):
                self.a.client.user.get_vip_info=AsyncMock(return_value=UserVipInfoResponse.model_validate(payload))
                self.assertEqual(await self.a.run({'op':'membership'}),expected)

if __name__=='__main__': unittest.main()
