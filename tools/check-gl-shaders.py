#!/usr/bin/env python3
"""Compiles the OpenGL shaders that are built from Java strings at runtime.

The Vulkan shaders are files, and `./gradlew compileShaders` refuses to build
when one of them is wrong. The OpenGL ones — the glow, the occlusion, the light
shafts, the composite — are assembled out of concatenated Java string literals
and handed to the driver while the game is running. Nothing checks them until
then, and when one fails the effect switches itself off for the session with a
line in the log. That is a whole class of fault that only ever appears on
somebody else's machine, hours after the typo.

This pulls each of them back out of the source and puts it through
glslangValidator, which is the same thing the driver will do.
"""
import io
import os
import re
import subprocess
import sys
import tempfile

SOURCE = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..',
                      'src/main/java/net/vulkanmodnext/vkimpl/VkTerrainRenderer.java')
# Every runtime program is built by one of these, and both wrap the body in the
# same version directive.
BUILDERS = ('buildQuadProgram(', 'compileGlShader(')


def literals_after(text, start):
    """The concatenated Java string literals of one call, joined.

    Comments are stepped over rather than treated as the end of the call: these
    shaders carry a paragraph of prose between almost every pair of lines, and
    an extractor that stops at the first slash truncates the body and then
    blames the shader for ending early. That happened on the first run of this
    script and reported five programs broken that were not.
    """
    parts = []
    pos = text.index('"', start)
    while True:
        if text[pos] == '"':
            end = pos + 1
            buf = []
            while text[end] != '"' or text[end - 1] == '\\':
                buf.append(text[end])
                end += 1
            parts.append(''.join(buf))
            pos = end + 1
            continue
        if text.startswith('//', pos):
            pos = text.index('\n', pos) + 1
            continue
        if text.startswith('/*', pos):
            pos = text.index('*/', pos) + 2
            continue
        if text.startswith(');', pos) or text.startswith(',', pos):
            break
        if text[pos] not in ' \t\r\n+':
            break
        pos += 1
    return ''.join(parts).replace('\\n', '\n').replace('\\"', '"').replace('\\\\', '\\')


def main():
    text = io.open(SOURCE, encoding='utf-8').read()
    lines = text[:].split('\n')
    failures = 0
    checked = 0
    for builder in BUILDERS:
        for match in re.finditer(re.escape(builder), text):
            start = match.end()
            # compileGlShader takes the stage first; skip to its second argument.
            if builder == 'compileGlShader(':
                if '"' not in text[start:start + 200]:
                    continue
            body = literals_after(text, start)
            if 'void main' not in body:
                continue
            stage = '.vert' if 'gl_Position' in body and 'gl_FragColor' not in body else '.frag'
            source = body if body.lstrip().startswith('#version') else '#version 120\n' + body
            line = text.count('\n', 0, match.start()) + 1
            with tempfile.NamedTemporaryFile('w', suffix=stage, delete=False) as handle:
                handle.write(source)
                path = handle.name
            try:
                result = subprocess.run(['glslangValidator', path],
                                        capture_output=True, text=True)
            finally:
                os.unlink(path)
            checked += 1
            if result.returncode != 0:
                failures += 1
                print('FAILED at %s:%d' % (os.path.basename(SOURCE), line))
                print(result.stdout.strip())
            else:
                print('ok   %s:%d (%s, %d lines)'
                      % (os.path.basename(SOURCE), line, stage[1:], source.count('\n')))
    print('\n%d checked, %d failed' % (checked, failures))
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
