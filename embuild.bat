set EMCC_DEBUG=1
emcc -O2 -s STACK_OVERFLOW_CHECK=1 -s ASSERTIONS=1 libwarble/src/warble.c libwarble/src/warble_complex.c libcorrect/src/reed-solomon/encode.c ^
libcorrect/src/reed-solomon/decode.c libcorrect/src/reed-solomon/polynomial.c ^
libcorrect/src/reed-solomon/reed-solomon.c -Ilibcorrect/include -Ilibwarble/include -o openwarble-emscripten.html ^
-s "EXPORTED_FUNCTIONS=['_warble_create', '_warble_init', '_warble_feed', '_warble_feed_window_size', '_warble_generate_window_size', '_warble_generate_pitch', '_warble_reed_encode_solomon', '_warble_reed_decode_solomon', '_warble_generate_signal', '_warble_cfg_get_payloadSize', '_warble_cfg_get_frequenciesIndexTriggersCount', '_warble_cfg_get_frequenciesIndexTriggers', '_warble_cfg_get_sampleRate', '_warble_cfg_get_block_length', '_warble_cfg_get_distance', '_warble_cfg_get_rs_message_length', '_warble_cfg_get_distance_last', '_warble_cfg_get_parsed', '_warble_cfg_get_shuffleIndex', '_warble_cfg_get_frequencies', '_warble_cfg_get_triggerSampleIndex', '_warble_cfg_get_triggerSampleIndexBegin', '_warble_cfg_get_triggerSampleRMS', '_warble_cfg_get_word_length', '_warble_cfg_get_window_length', '_warble_free', '_warble_test_js']" ^
-s "EXTRA_EXPORTED_RUNTIME_METHODS=['ccall', 'cwrap','getValue','ALLOC_NORMAL']"
set EMCC_DEBUG=0
