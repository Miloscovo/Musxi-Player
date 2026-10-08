import asyncio
import unittest
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
from types import SimpleNamespace as S
from unittest.mock import AsyncMock
from qq_bridge import Adapter, Credential, QRCodeLoginEvents, PublicError
from qqmusic_api.core.response import snapshot_payload
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

if __name__=='__main__': unittest.main()
