import importlib.util, pathlib, tempfile, unittest, io
import numpy as np
import soundfile as sf
spec=importlib.util.spec_from_file_location('extractor',pathlib.Path(__file__).with_name('extract_benchmark_fixtures.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class ExtractionTest(unittest.TestCase):
    def test_same_sentence_id_preserves_distinct_recordings_and_hashes(self):
        rows=[]
        for index in range(2):
            stream=io.BytesIO();sf.write(stream,np.sin(np.arange(64000)*(.03+index*.01))*.1,16000,format='WAV',subtype='FLOAT')
            rows.append(dict(id=7,audio=dict(bytes=stream.getvalue()),raw_transcription='同じ参照文。'))
        with tempfile.TemporaryDirectory() as directory:
            fixtures=module.extract_rows(rows,pathlib.Path(directory),20)
            self.assertEqual(len(fixtures),2)
            self.assertNotEqual(fixtures[0]['file'],fixtures[1]['file'])
            self.assertNotEqual(fixtures[0]['sha256'],fixtures[1]['sha256'])
            import hashlib
            for fixture in fixtures:self.assertEqual(hashlib.sha256((pathlib.Path(directory)/fixture['file']).read_bytes()).hexdigest(),fixture['sha256'])
if __name__=='__main__':unittest.main()