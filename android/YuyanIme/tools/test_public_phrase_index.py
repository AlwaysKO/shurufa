import struct,unittest
from build_public_phrase_index import encode_index,extract_words
class IndexTest(unittest.TestCase):
    def test_exact_sorted_unique_utf8_entries(self):
        data=encode_index(['你好','欢迎','你好']);self.assertEqual(b'T9WORD1\0',data[:8]);self.assertEqual(2,struct.unpack_from('<I',data,8)[0])
        offsets=struct.unpack_from('<3I',data,12);payload=data[24:]
        self.assertEqual(['你好','欢迎'],[payload[offsets[i]:offsets[i+1]].decode() for i in range(2)])
    def test_original_rows_only_no_sentence_generation(self):
        self.assertEqual({'你好','欢迎回来'},extract_words('# hi\n---\nname: demo\n...\n你好\tni hao\t1\n欢迎回来\t100\n坏😀\t10\n单\tdan\t10\n'))
    def test_empty_index_has_one_zero_offset(self):
        self.assertEqual(b'T9WORD1\0'+struct.pack('<II',0,0),encode_index([]))
if __name__=='__main__':unittest.main()
