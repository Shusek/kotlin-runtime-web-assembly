(component
  (core module $main
    (func (export "run") (result i32) i32.const 0))
  (core instance $main (instantiate $main))
  (func $run (result (result)) (canon lift (core func $main "run")))
  (instance $cli (export "run" (func $run)))
  (export "wasi:cli/run@0.2.0" (instance $cli)))
