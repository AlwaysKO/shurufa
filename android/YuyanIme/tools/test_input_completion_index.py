import struct
import unittest

from build_input_completion_index import completion_rows, encode_index, parse_readings


class InputCompletionIndexTest(unittest.TestCase):
    def test_only_three_syllable_whole_words_get_initial_codes(self):
        rows = completion_rows([('就可以', 'jiu ke yi', 100), ('良口镇', 'liang kou zhen', 0),
                                ('美国最高法院', 'mei guo zui gao fa yuan', 1000)], set())
        self.assertEqual(['就可以', '良口镇'], [r[1] for r in rows if r[0] == 'a559'])
        self.assertFalse(any(r[0] == 'a649439' for r in rows))

    def test_only_curated_long_phrases_get_letter_and_numeric_prefix_indexes(self):
        rows = completion_rows([('飞流直下三千尺', 'fei liu zhi xia san qian chi', 20),
                                ('第二届中国', 'di er jie zhong guo', 1000)], {'飞流直下三千尺'})
        self.assertEqual({'n3345489449427267426244', 'pfeiliuzhixiasanqianchi'}, {r[0] for r in rows})

    def test_parse_rejects_unknown_readings_and_mismatched_syllables(self):
        text = '---\n...\n就可以\tjiu ke yi\t100\n良口镇\t100\n错误\tcuo\t10\n脏读音\ta ! a\t10\n'
        self.assertEqual([('就可以', 'jiu ke yi', 100)], parse_readings(text))

    def test_two_and_three_character_words_have_initial_and_full_lookup_channels(self):
        rows = completion_rows([('南山', 'nan shan', 522), ('不知道', 'bu zhi dao', 1000)], set())
        self.assertEqual({'a67', 'ins', 'pnanshan', 'n6267426', 'mnshan', 'm67426'},
                         {r[0] for r in rows if r[1] == '南山'})
        self.assertEqual({'a293', 'ibzd', 'pbuzhidao', 'n28944326', 'mbuzdao', 'm289326'},
                         {r[0] for r in rows if r[1] == '不知道'})

    def test_parse_accepts_two_syllables_without_accepting_mismatched_readings(self):
        self.assertEqual([('南山', 'nan shan', 522)],
                         parse_readings('...\n南山\tnan shan\t522\n南山\tnan\t522\n'))

    def test_ambiguous_two_digit_initials_keep_only_highest_public_frequencies(self):
        entries = [(chr(0x4e00 + i) + '山', 'nan shan', i) for i in range(80)]
        rows = completion_rows(entries, set())
        self.assertEqual(list(range(79, 15, -1)), [r[3] for r in rows if r[0] == 'a67'])
        self.assertEqual(list(range(79, 47, -1)), [r[3] for r in rows if r[0] == 'ins'])
        self.assertEqual(80, sum(r[0] == 'pnanshan' for r in rows))

    def test_three_letter_initials_are_bounded_but_zero_frequency_digit_places_remain(self):
        entries = [(chr(0x4e00 + i) + '口镇', 'liang kou zhen', i) for i in range(40)]
        entries.append(('良口镇', 'liang kou zhen', 0))
        rows = completion_rows(entries, set())
        self.assertEqual(32, sum(r[0] == 'ilkz' for r in rows))
        self.assertTrue(any(r[0] == 'a559' and r[1] == '良口镇' for r in rows))

    def test_mixed_initial_full_keys_recall_public_only_words(self):
        rows = completion_rows([('爱豆', 'ai dou', 100), ('充电宝', 'chong dian bao', 50)], set())
        self.assertTrue(any(row[0] == 'madou' and row[1] == '爱豆' for row in rows))
        self.assertTrue(any(row[0] == 'm2368' and row[1] == '爱豆' for row in rows))
        self.assertTrue(any(row[0] == 'mchongdbao' and row[1] == '充电宝' for row in rows))
        self.assertTrue(any(row[0] == 'm246643226' and row[1] == '充电宝' for row in rows))

    def test_wide_prefix_fallback_keeps_high_frequency_tail_without_starting_next_syllable(self):
        entries = []
        for i in range(600):
            tail = ''.join(chr(ord('a') + ((i // 26 ** shift) % 26)) for shift in (2, 1, 0))
            entries.append((chr(0x4e00 + i) + '安', 'xi a' + tail, 1))
        entries += [('喜爱', 'xi azzzz', 10000), ('喜爱', 'xi azzz', 9000), ('下班', 'xia ban', 99999)]
        rows = completion_rows(entries, set())
        for key in ('bxia', 'b942'):
            bucket = [row for row in rows if row[0] == key]
            self.assertEqual(64, len(bucket))
            self.assertEqual('喜爱', bucket[0][1])
            self.assertFalse(any(row[1] == '下班' for row in bucket))
            self.assertEqual(64, len({(row[1], row[2]) for row in bucket}))
            self.assertEqual(64, len({row[1] for row in bucket}))
        self.assertFalse(any(row[0] == 'bxi' for row in rows))

    def test_wide_two_word_mixed_bucket_does_not_spend_slots_on_partial_second_syllables(self):
        entries = [(chr(0x4e00+i)+'安', 'xi aa'+''.join(chr(97+(i//26**j)%26) for j in (2,1,0)), 1000) for i in range(4100)]
        entries.append(('喜啊', 'xi aa', 1))
        bucket = [r for r in completion_rows(entries, set()) if r[0]=='bxaa']
        self.assertEqual(['喜啊'], [r[1] for r in bucket])

    def test_binary_offsets_are_complete_and_deterministic(self):
        rows = completion_rows([('就可以', 'jiu ke yi', 100)], set())
        data = encode_index(rows)
        self.assertEqual(b'T9COMP1\0', data[:8])
        self.assertEqual(len(rows), struct.unpack_from('<I', data, 8)[0])
        self.assertEqual(0, struct.unpack_from('<I', data, 12)[0])
        header_size = 12 + 4 * (len(rows) + 1)
        self.assertEqual(len(data)-header_size, struct.unpack_from('<I', data, 12 + 4 * len(rows))[0])
        self.assertEqual(data, encode_index(rows))


if __name__ == '__main__':
    unittest.main()
