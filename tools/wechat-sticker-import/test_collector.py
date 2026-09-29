import hashlib
import hmac
import io
import struct
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from Crypto.Cipher import AES
from PIL import Image
import collector as c

KEY = b'K' * 32
SALT = b'S' * 16

def page(number=1, fill=b'A'):
    start = 16 if number == 1 else 0
    iv = b'I' * 16
    cipher = AES.new(KEY, AES.MODE_CBC, iv).encrypt(fill * (4016-start))
    body = (SALT if number == 1 else b'') + cipher + iv
    mk = hashlib.pbkdf2_hmac('sha512', KEY, bytes(x ^ 0x3a for x in SALT), 2, 32)
    return body + hmac.new(mk, body[start:] + struct.pack('<I', number), 'sha512').digest()

def checksum(data, state=(0,0), endian='<'):
    a,b=state
    words=struct.unpack(endian+'I'*(len(data)//4), data)
    for i in range(0,len(words),2):
        a=(a+words[i]+b)&0xffffffff
        b=(b+words[i+1]+a)&0xffffffff
    return a,b

def wal(frames, endian='<'):
    header=struct.pack('>6I',0x377f0682 if endian=='<' else 0x377f0683,3007000,4096,0,1,2)
    state=checksum(header,endian=endian)
    result=header+struct.pack('>2I',*state)
    for number,size,payload in frames:
        head=struct.pack('>4I',number,size,1,2)
        state=checksum(head[:8]+payload,state,endian)
        result+=head+struct.pack('>2I',*state)+payload
    return result

def picture(fmt='GIF', animated=False):
    out=io.BytesIO()
    first=Image.new('RGB',(8,8),'red')
    args={'save_all':True,'append_images':[Image.new('RGB',(8,8),'blue')]} if animated else {}
    first.save(out,format=fmt,**args)
    return out.getvalue()

class CryptoTests(unittest.TestCase):
    def test_hmac_rejects_wrong_key_and_page_number(self):
        self.assertTrue(c.verify_page(page(),KEY,SALT,1))
        self.assertFalse(c.verify_page(page(),b'X'*32,SALT,1))
        self.assertFalse(c.verify_page(page(2),KEY,SALT,3))
    def test_decrypt_and_truncation(self):
        self.assertEqual(c.decrypt_page(page(2),KEY,SALT,2)[:4016],b'A'*4016)
        with self.assertRaises(c.CollectorError): c.decrypt_database(page()[:-1],b'',KEY)
    def test_mask_boundary_and_target_verification(self):
        clear=b"x'"+KEY.hex().encode()+SALT.hex().encode()+b"'"
        blob=bytes(v^c.MASK[i%32] for i,v in enumerate(clear))
        chunks=[(0,b'a'*23+blob[:73]),(96,blob[73:]+b'z')]
        self.assertEqual(c.find_key_in_chunks(chunks,page()),KEY)
        self.assertIsNone(c.find_key_in_chunks(chunks,page().replace(SALT,b'T'*16,1)))
        self.assertIsNone(c.find_key_in_chunks([(0,blob[:73]),(100,blob[73:])],page()))
    def test_wal_commit_and_uncommitted(self):
        for endian in ('<','>'):
            frames=c.committed_wal_pages(wal([(2,2,page(2,b'B')),(2,0,page(2,b'C'))],endian))
            self.assertEqual(frames,(2,{2:page(2,b'B')}))
    def test_wal_stale_generation_and_partial_tail(self):
        data=bytearray(wal([(2,2,page(2))]));data[40:48]=b'Z'*8
        self.assertEqual(c.committed_wal_pages(bytes(data)),(None,{}))
        valid=wal([(2,2,page(2))])
        self.assertEqual(c.committed_wal_pages(valid+b'partial'),(2,{2:page(2)}))
    def test_decrypt_database_applies_only_last_commit(self):
        merged=c.decrypt_database(page()+page(2),wal([(2,2,page(2,b'B')),(2,0,page(2,b'C'))]),KEY)
        self.assertEqual(merged[4096:4096+4016],b'B'*4016)
    def test_wal_header_corruption_fails_closed(self):
        data=bytearray(wal([]));data[20]^=1
        with self.assertRaises(c.CollectorError): c.committed_wal_pages(bytes(data))
    def test_wal_corruption_fails_closed(self):
        data=bytearray(wal([(2,2,page(2))]));data[-1]^=1
        with self.assertRaises(c.CollectorError): c.committed_wal_pages(bytes(data))
    def test_snapshot_retries_and_never_accepts_unstable_pair(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'emoticon.db';path.write_bytes(page())
            original=c._read_pair
            count=[0]
            def changing(p):
                count[0]+=1
                return (bytes([count[0]])+page()[1:],b'')
            with patch.object(c,'_read_pair',side_effect=changing):
                with self.assertRaisesRegex(c.CollectorError,'snapshot_unstable'): c.stable_snapshot(path)
            self.assertGreaterEqual(count[0],4)
            self.assertEqual(c.stable_snapshot(path),(page(),b''))

class ImageTests(unittest.TestCase):
    def test_jpeg_png_webp_are_decoded_by_actual_type(self):
        for fmt in ('JPEG','PNG','WEBP'):
            data=picture(fmt)
            result=c.validate_image(data,hashlib.md5(data).hexdigest())
            self.assertEqual(result['format'],fmt.lower())
            self.assertEqual(result['frames'],1)
    def test_original_single_and_multiframe(self):
        for animated in (False,True):
            data=picture(animated=animated)
            result=c.validate_image(data,hashlib.md5(data).hexdigest())
            self.assertEqual(result['sha256'],hashlib.sha256(data).hexdigest())
            self.assertEqual(result['frames'],2 if animated else 1)
    def test_gif_trailer_can_precede_non_image_padding(self):
        data=picture()+b'\0\0'
        self.assertEqual(c.validate_image(data,hashlib.md5(data).hexdigest())['frames'],1)
    def test_gif_without_trailer_is_rejected(self):
        data=picture()[:-1]
        with self.assertRaises(c.CollectorError): c.validate_image(data,hashlib.md5(data).hexdigest())
    def test_invalid_md5_truncated_and_size(self):
        data=picture(animated=True)
        for invalid,md5 in [(data,'0'*32),(data[:-8],hashlib.md5(data[:-8]).hexdigest())]:
            with self.assertRaisesRegex(c.CollectorError,'invalid_image'): c.validate_image(invalid,md5)
    def test_only_source_url_can_upgrade_http_before_network(self):
        self.assertEqual(c.source_cdn_url('http://wxapp.tc.qq.com/a'),'https://wxapp.tc.qq.com/a')
        for url in ('http://evil.com/a','http://wxapp.tc.qq.com:80/a'):
            with self.assertRaises(c.CollectorError): c.source_cdn_url(url)
    def test_legacy_cdn_uses_only_verified_fixed_https_alias(self):
        self.assertEqual(c.source_cdn_url('http://vweixinf.tc.qq.com/a?token=x'),'https://wxapp.tc.qq.com/a?token=x')
        self.assertEqual(c.source_cdn_url('https://vweixinf.tc.qq.com/a'),'https://wxapp.tc.qq.com/a')
        for url in ('https://vweixinf.tc.qq.com.evil/a','https://u@vweixinf.tc.qq.com/a','https://vweixinf.tc.qq.com:80/a'):
            with self.assertRaises(c.CollectorError): c.source_cdn_url(url)
    def test_url_and_redirect_boundary(self):
        c.validate_cdn_url('https://wxapp.tc.qq.com/a')
        for url in ['http://wxapp.tc.qq.com/a','https://wxapp.tc.qq.com.evil/a','https://a@wxapp.tc.qq.com/a','https://wxapp.tc.qq.com:444/a','https://127.0.0.1/a']:
            with self.assertRaises(c.CollectorError): c.validate_cdn_url(url)
        handler=c.CdnRedirectHandler()
        with self.assertRaises(c.CollectorError): handler.redirect_request(None,None,302,'',{},'https://evil.com/a')
    def test_download_bounds_and_every_redirect_is_validated(self):
        data=picture()
        class Response:
            url='https://wxapp.tc.qq.com/a'
            headers={'Content-Length':str(c.MAX_IMAGE+1)}
            def __enter__(self): return self
            def __exit__(self,*args): pass
            def read(self,size): raise AssertionError('oversized body must not be read')
        with patch.object(c.urllib.request,'build_opener') as build:
            build.return_value.open.return_value=Response()
            with self.assertRaisesRegex(c.CollectorError,'download_failed'): c.download_original(Response.url)
            self.assertEqual(build.return_value.open.call_count,2)
        for target in ('http://wxapp.tc.qq.com/a','https://wxapp.tc.qq.com:80/a'):
            with self.assertRaises(c.CollectorError): c.CdnRedirectHandler().redirect_request(None,None,302,'',{},target)
    def test_download_uses_single_read_and_checks_deadline_after_eof(self):
        clock=[0.0]
        reads=[]
        class SlowResponse:
            url='https://wxapp.tc.qq.com/a'
            headers={}
            def __enter__(self): return self
            def __exit__(self,*args): pass
            def read(self,size): raise AssertionError('must not wait for a full block')
            def read1(self,size):
                reads.append(size)
                clock[0]+=46
                return b''
        with patch.object(c.urllib.request,'build_opener') as build, patch.object(c.time,'monotonic',side_effect=lambda:clock[0]):
            build.return_value.open.return_value=SlowResponse()
            with self.assertRaisesRegex(c.CollectorError,'download_failed'):
                c.download_original(SlowResponse.url)
            self.assertEqual(len(reads),2)
            self.assertEqual(build.return_value.open.call_args.kwargs['timeout'],15)
    def test_download_checks_cancellation_immediately_after_single_read(self):
        cancelled=[False]
        reads=[]
        class Response:
            url='https://wxapp.tc.qq.com/a'
            headers={}
            def __enter__(self): return self
            def __exit__(self,*args): pass
            def read(self,size): raise AssertionError('must not wait for a full block')
            def read1(self,size):
                reads.append(size)
                cancelled[0]=True
                return b''
        with patch.object(c.urllib.request,'build_opener') as build:
            build.return_value.open.return_value=Response()
            with self.assertRaises(c.CollectionCancelled):
                c.download_original(Response.url,cancel=lambda:cancelled[0])
            self.assertEqual(len(reads),1)
            self.assertEqual(build.return_value.open.call_count,1)
    def test_download_preserves_bytes_from_partial_single_reads(self):
        chunks=iter((b'a',b'bc',b''))
        class Response:
            url='https://wxapp.tc.qq.com/a'
            headers={}
            def __enter__(self): return self
            def __exit__(self,*args): pass
            def read(self,size): raise AssertionError('must not wait for a full block')
            def read1(self,size): return next(chunks)
        with patch.object(c.urllib.request,'build_opener') as build:
            build.return_value.open.return_value=Response()
            self.assertEqual(c.download_original(Response.url),b'abc')
    def test_later_gif_frame_cannot_expand_canvas_before_decode(self):
        from PIL import GifImagePlugin
        out=io.BytesIO()
        Image.new('RGB',(16,16),'blue').save(out,format='GIF')
        second=out.getvalue()
        offset=13+(3*2**((second[10]&7)+1) if second[10]&0x80 else 0)
        data=picture()[:-1]+second[offset:]
        original_load=GifImagePlugin.GifImageFile.load
        decoded=[]
        def track_load(image,*args,**kwargs):
            decoded.append(image.size)
            return original_load(image,*args,**kwargs)
        with patch.object(c,'MAX_PIXELS',100), patch.object(c,'MAX_FRAME_PIXELS',200), patch.object(GifImagePlugin.GifImageFile,'load',track_load):
            with self.assertRaisesRegex(c.CollectorError,'invalid_image'):
                c.validate_image(data,hashlib.md5(data).hexdigest())
        self.assertNotIn((16,16),decoded)
    def test_image_resource_and_cache_limits(self):
        data=picture(animated=True);md5=hashlib.md5(data).hexdigest()
        with patch.object(c,'MAX_FRAME_PIXELS',64):
            with self.assertRaises(c.CollectorError): c.validate_image(data,md5)
        with tempfile.TemporaryDirectory() as directory, patch.object(c,'MAX_CACHE',1):
            with self.assertRaises(c.CollectorError): c.cache_original(Path(directory),data,c.validate_image(data,md5))
            self.assertEqual(list(Path(directory).iterdir()),[])
    def test_cache_exclusive_open_race_never_deletes_other_writer(self):
        with tempfile.TemporaryDirectory() as directory:
            data=picture();meta=c.validate_image(data,hashlib.md5(data).hexdigest())
            target=Path(directory)/(meta['sha256']+'.gif')
            original=Path.open
            def race(path,*args,**kwargs):
                if args and args[0]=='xb':
                    with original(path,'wb') as stream: stream.write(data)
                    raise FileExistsError()
                return original(path,*args,**kwargs)
            with patch.object(Path,'open',race):
                with self.assertRaises(c.CollectorError): c.cache_original(Path(directory),data,meta)
            self.assertEqual(target.read_bytes(),data)
    def test_cache_same_bytes_different_names_and_no_secrets(self):
        with tempfile.TemporaryDirectory() as directory:
            data=picture();meta=c.validate_image(data,hashlib.md5(data).hexdigest())
            a=c.cache_original(Path(directory),data,meta)
            b=c.cache_original(Path(directory),data,meta)
            self.assertEqual(a,b)
            self.assertEqual(Path(a).read_bytes(),data)
            self.assertEqual(len(list(Path(directory).iterdir())),1)

class CollectionTests(unittest.TestCase):
    def test_collect_fixed_errors_continues_and_reports_progress(self):
        data=picture();md5=hashlib.md5(data).hexdigest();events=[]
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(c,'stable_snapshot',return_value=(page(),b'')), patch.object(c,'find_wechat_key',return_value=KEY), patch.object(c,'decrypt_database',return_value=b'plain'), patch.object(c,'favorite_rows',return_value=[(1,md5,'https://wxapp.tc.qq.com/a'),(2,'0'*32,'https://wxapp.tc.qq.com/b')]), patch.object(c,'download_original',return_value=data):
                result=c.collect(Path(directory)/'account',Path(directory)/'cache','Weixin.exe',progress=events.append)
            self.assertEqual(result['total'],2)
            self.assertEqual(len(result['items']),1)
            self.assertEqual(result['sourceErrors'],[{'code':'invalid_image','count':1}])
            self.assertEqual(events[-1]['processed'],2)
            self.assertNotIn('https',str(result))
    def test_cache_cannot_write_inside_wechat_source(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(c.CollectorError,'collector_failed'):
                c.collect(directory,Path(directory)/'cache','Weixin.exe')
            self.assertFalse((Path(directory)/'cache').exists())
    def test_cancel_is_not_swallowed(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(c.CollectionCancelled): c.collect(directory,directory,'Weixin.exe',cancel=lambda:True)
    def test_sqlite_temporary_query_storage_is_forced_to_memory(self):
        commands=[]
        class Connection:
            def deserialize(self,data): pass
            def execute(self,sql,*args): commands.append(sql);return self
            def fetchall(self): return []
            def close(self): pass
        with patch.object(c.sqlite3,'Connection',Connection), patch.object(c.sqlite3,'connect',return_value=Connection()):
            c.favorite_rows(b'plain')
        self.assertIn('PRAGMA temp_store=MEMORY',commands)
        self.assertLess(commands.index('PRAGMA temp_store=MEMORY'),len(commands)-1)
    def test_plain_database_query_is_memory_only(self):
        import sqlite3
        if not hasattr(sqlite3.Connection,'serialize'): self.skipTest('native Python 3.12 test')
        db=sqlite3.connect(':memory:')
        try:
            db.executescript("CREATE TABLE kFavEmoticonOrderTable(md5 TEXT); CREATE TABLE kNonStoreEmoticonTable(md5 TEXT,cdn_url TEXT); INSERT INTO kFavEmoticonOrderTable VALUES ('abc');")
            plain=db.serialize()
        finally: db.close()
        self.assertEqual(c.favorite_rows(plain),[(1,'abc','')])
        with self.assertRaises(c.CollectorError): c.favorite_rows(b'broken')

if __name__=='__main__': unittest.main()
