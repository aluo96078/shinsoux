"""Writes the compatibility bootstrap as plain JavaScript (for core_test). usage: <kt> <out.js>"""
import pathlib
import sys

source = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
marker = 'IOS_JAVASCRIPTCORE_BOOTSTRAP: String = """'
start = source.index(marker) + len(marker)
pathlib.Path(sys.argv[2]).write_text(source[start:source.index('"""', start)], encoding="utf-8")
