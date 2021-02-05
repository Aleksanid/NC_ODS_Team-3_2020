import {Component} from '@angular/core';
import {AuthService} from './services/AuthService';
import {AlertBarService} from './services/AlertBarService';

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent {
  title: 'LogViewer';
  errorMessage: string;
  confirmMessage: string;

  constructor(private authService: AuthService, private alertBarService: AlertBarService) {
    alertBarService.topBar = this;
  }

  public setErrorMessage(message: string) {
    this.errorMessage = message;
  }

  public setConfirmMessage(message: string) {
    this.confirmMessage = message;
  }


  isLoginned(): boolean {
    return this.authService.isLoggedIn();
  }

  isAdmin(): boolean {
    return this.authService.isAdmin();
  }

  logout(): void {
    this.authService.logout();
  }
}
