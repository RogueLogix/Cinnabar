# 1.21.6-0.0.2-alpha
 - increase MemoryStack size to 256kb (fixes stack overflow crash with nvidia GPUs)
 - allow GrowableMemoryStack to overrun a single block for a single allocation (fixes stack overflow crash from too many render chunks)
 - sync pooled buffer allocation, should prevent crash in VMA (#4)
 - increase cleanup and semaphore wait thread priority

# 1.21.6-0.0.1-alpha
 - Initial release