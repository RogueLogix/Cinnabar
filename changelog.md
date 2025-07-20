# 1.21.6-0.0.5-alpha

- update Neo to 21.6.20-beta
- Drop VK requirement to 1.2, additionally drop sync2 and logicop requirement (logic op vaguely simulated when unavailable)
- Enable VK_KHR_portability_subset if available (MacOS)

# 1.21.6-0.0.4-alpha

- fix neo version range
- update neo
- make sure the framebuffer will get resized when the swapchain does

# 1.21.6-0.0.3-alpha

- fix fence reset and command pool flags in early loading screen
- add VMA stats to F3 screen
- fix VK sync issues (#2, #3, #5)
- fix memory leaks

# 1.21.6-0.0.2-alpha

- increase MemoryStack size to 256kb (fixes stack overflow crash with nvidia GPUs)
- allow GrowableMemoryStack to overrun a single block for a single allocation (fixes stack overflow crash from too many render chunks)
- sync pooled buffer allocation, should prevent crash in VMA (#4)
- increase cleanup and semaphore wait thread priority

# 1.21.6-0.0.1-alpha

- Initial release