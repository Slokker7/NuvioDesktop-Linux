-- Diagnostic CLI only. Observe playback position rather than decoder startup.
local utils = require 'mp.utils'
local maximum = -1
local backend = 'no'
local function sample()
    local position = mp.get_property_number('time-pos', -1)
    maximum = math.max(maximum, position)
    local current = mp.get_property('hwdec-current', 'no')
    if current ~= 'no' then backend = current end
end
mp.observe_property('time-pos', 'number', function(_, value)
    if value then maximum = math.max(maximum, value) end
    sample()
end)
mp.add_hook('on_unload', 50, sample)
mp.register_event('end-file', function(event)
    sample()
    print('NUVIO_PROGRESS ' .. utils.format_json({
        position=maximum, hwdec=backend, reason=event.reason, error=event.error
    }))
end)
