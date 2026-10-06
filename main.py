from fastapi import FastAPI

from routers import expenses_router, groups_router, notifications_router

app = FastAPI(title="SplitMate Backend")


@app.get("/")
def read_root():
    return {
        "status": "ok",
        "message": "SplitMate backend is running",
    }


app.include_router(groups_router.router)
app.include_router(expenses_router.router)
app.include_router(notifications_router.router)
