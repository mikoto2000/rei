import sys
import torch
print('python', sys.version.split()[0])
print('torch', torch.__version__)
print('cuda_available', torch.cuda.is_available())
print('cuda_version', torch.version.cuda)
if torch.cuda.is_available():
    print('device', torch.cuda.get_device_name(0))
    print('vram_gb', round(torch.cuda.get_device_properties(0).total_memory / 1024**3, 1))
