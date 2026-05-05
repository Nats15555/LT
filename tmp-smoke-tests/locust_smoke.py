from locust import HttpUser, task, between
class QuickUser(HttpUser):
    wait_time = between(0.1, 0.2)
    @task
    def health(self):
        self.client.get('/api/test/ping')
