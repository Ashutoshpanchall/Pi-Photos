"""PiPhotos Storage Node server package."""

__version__ = "1.0.0"

try:
    from main import app
except ImportError:
    app = None  # type: ignore

__all__ = ["app", "__version__"]
