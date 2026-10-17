import struct,sys,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
from leo_models import Geometry,UnsupportedModel,bone,transform
from leo_code import Image,explore

def packet(op,*args):return bytes([op,0,0,0])+struct.pack('<'+'I'*len(args),*args)
def vertex(x,y,z):return packet(0x23,(x&65535)|((y&65535)<<16),z&65535)

class GeometryTests(unittest.TestCase):
    def test_triangle_strip_winding(self):
        data=packet(0x40,2)+vertex(0,0,0)+vertex(4096,0,0)+vertex(0,4096,0)+vertex(4096,4096,0)+packet(0x41)
        triangles=Geometry().draw(data)
        self.assertEqual(len(triangles),6)
        for a,b,c in [triangles[:3],triangles[3:]]:
            self.assertGreater((b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]),0)
    def test_signed_vertex_and_delta(self):
        data=packet(0x40,0)+vertex(-4096,0,0)+packet(0x28,0x3ff)+packet(0x25,4096)+packet(0x41)
        vertices=Geometry().draw(data)
        self.assertEqual(vertices[0][:3],[-1,0,0])
        self.assertEqual(vertices[1][0],-1-1/4096)
        self.assertEqual(vertices[2][:3],[1,0,0])
    def test_matrix_restore(self):
        g=Geometry();g.stack[1]=bone(struct.pack('<Hh3i',6,0,4096,8192,12288),0)
        data=packet(0x14,1)+packet(0x40,0)+vertex(0,0,0)*3+packet(0x41)
        self.assertEqual(g.draw(data)[0][:3],[1,2,3])
    def test_pivot_layout(self):
        m=bone(struct.pack('<Hhhh',1|4|8|256|512,0,2048,3072),0)
        self.assertEqual(transform(m,[1,2,3]),[-1,-.5,3.75])
    def test_reject_unknown_and_truncated(self):
        for data in (packet(0x15),bytes([0x23,0,0,0]),packet(0x40,0)+vertex(0,0,0)+packet(0x41)):
            with self.assertRaises(ValueError):Geometry().draw(data)
        with self.assertRaises(UnsupportedModel):Geometry().draw(packet(0x14,0))

class LiteralBranchTests(unittest.TestCase):
    def test_arm_to_thumb_literal_branch(self):
        image=Image('code',0x2000000,struct.pack('<3I',0xe59f0000,0xe12fff10,0x200000d)+bytes.fromhex('01207047'))
        flow=explore([image],[(image.address,False)])
        self.assertEqual(flow['edges'][0]['target'],0x200000c)
        self.assertTrue(flow['edges'][0]['thumb'])
        self.assertEqual(flow['edges'][0]['evidence'],'literal-register')
        self.assertEqual(flow['instruction_count'],4)
    def test_overwrite_invalidates_literal(self):
        image=Image('code',0x2000000,struct.pack('<4I',0xe59f0004,0xe3a00000,0xe12fff10,0x2000011))
        flow=explore([image],[(image.address,False)])
        self.assertFalse(flow['edges'])
        self.assertEqual(flow['stops'][0]['reason'],'indirect branch')
    def test_call_clobbers_volatile(self):
        image=Image('code',0x2000000,struct.pack('<5I',0xe59f0008,0xeb000000,0xe12fff10,0xe12fff1e,0x2000015))
        flow=explore([image],[(image.address,False)])
        self.assertEqual(len(flow['edges']),1)
        self.assertEqual(flow['edges'][0]['kind'],'call')

if __name__=='__main__':unittest.main()
