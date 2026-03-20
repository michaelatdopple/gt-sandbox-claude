import typescript from '@rollup/plugin-typescript';

export default [
  {
    input: 'sdk/loop-sdk.ts',
    output: {
      file: 'app/src/main/assets/loop-sdk.js',
      format: 'iife',
    },
    plugins: [
      typescript({ tsconfig: './tsconfig.json', declaration: false })
    ]
  },
  {
    input: 'sdk/internal-sdk.ts',
    output: {
      file: 'app/src/main/assets/internal-sdk.js',
      format: 'iife',
    },
    plugins: [
      typescript({ tsconfig: './tsconfig.json', declaration: false })
    ]
  }
];
